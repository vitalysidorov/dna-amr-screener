package by.vs.dnaamrscreener.controller;

import by.vs.dnaamrscreener.dto.ReportResult;
import by.vs.dnaamrscreener.exception.FileAlreadyProcessingException;
import by.vs.dnaamrscreener.service.DnaProcessingService;
import jakarta.servlet.http.HttpServletRequest;

import lombok.extern.slf4j.Slf4j;
import org.biojava.nbio.genome.io.fastq.Fastq;
import org.biojava.nbio.genome.io.fastq.FastqReader;
import org.biojava.nbio.genome.io.fastq.SangerFastqReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RestController
@RequestMapping("/api/v1")
public class DnaAnalysisController {

    private final StringRedisTemplate sessionRedis;
    private final DnaProcessingService processingService;

    @Value("${spring.application.analyze-parallelism}")
    private int analyzeParallelism;
    @Value("${spring.application.batch-size}")
    private int batchSize;
    @Value("${screener.lsh.min-avg-phred}")
    private double minAvgPhred = 20.0;

    @Value("${screener.lsh.kmer-size}")
    private Integer defKmer;
    @Value("${screener.lsh.num-hashes}")
    private Integer defHashes;
    @Value("${screener.lsh.bands}")
    private Integer defBands;
    @Value("${screener.lsh.rows-per-band}")
    private Integer defRows;

    public DnaAnalysisController(@Qualifier("sessionRedisTemplate") StringRedisTemplate sessionRedis,
                                 DnaProcessingService processingService) {
        this.sessionRedis = sessionRedis;
        this.processingService = processingService;
    }

    @PostMapping(value = "/analyze", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<?> analyzeStream(
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "strict") String profile,
            @RequestParam(defaultValue = "true") boolean finalize,

            @RequestHeader(value = "X-File-Hash", required = false) String fileHash,
            @RequestHeader(value = "X-LSH-Kmer-Size", required = false) Integer kmerSize,
            @RequestHeader(value = "X-LSH-Num-Hashes", required = false) Integer numHashes,
            @RequestHeader(value = "X-LSH-Bands", required = false) Integer bands,
            @RequestHeader(value = "X-LSH-Rows-Per-Band", required = false) Integer rowsPerBand,

            HttpServletRequest request) {

        final int finalKmer = (kmerSize != null) ? kmerSize : defKmer;
        final int finalHashes = (numHashes != null) ? numHashes : defHashes;
        final int finalBands = (bands != null) ? bands : defBands;
        final int finalRows = (rowsPerBand != null) ? rowsPerBand : defRows;

        DnaProcessingService.DetectionProfile detProfile = "relaxed".equalsIgnoreCase(profile)
                ? DnaProcessingService.DetectionProfile.RELAXED
                : DnaProcessingService.DetectionProfile.STRICT;

        log.info("analyzeStream start sessionId={} finalize={} fileHash={}",
                sessionId, finalize, fileHash);

        String lockKey = null;
        if (fileHash != null && !fileHash.isBlank()) {
            lockKey = "lock:file:" + fileHash;
            Boolean acquired = sessionRedis.opsForValue()
                    .setIfAbsent(lockKey, "processing", 15, TimeUnit.MINUTES);
            if (Boolean.FALSE.equals(acquired)) {
                throw new FileAlreadyProcessingException(
                        "файл уже отправлен и обрабатывается сервером");
            }
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore semaphore = new Semaphore(analyzeParallelism);

            InputStream networkStream = request.getInputStream();
            FastqReader fastqReader = new SangerFastqReader();
            Iterable<Fastq> records = fastqReader.read(networkStream);

            List<Future<?>> batchFutures = new ArrayList<>(batchSize);
            int counter = 0;
            AtomicLong processed = new AtomicLong();

            for (Fastq record : records) {
                String sequence = record.getSequence();
                String quality = record.getQuality();

                if (sequence == null || quality == null
                        || sequence.length() != quality.length()) {
                    continue;
                }
                if (averagePhred(quality) < minAvgPhred) {
                    continue;
                }

                final String seq = sequence.toUpperCase();

                batchFutures.add(executor.submit(() -> {
                    semaphore.acquireUninterruptibly();
                    try {
                        processingService.queryLshAndIncrementDynamic(seq, sessionId, finalKmer,
                                finalHashes, finalBands, finalRows);

                        long n = processed.incrementAndGet();
                        if (n % 10_000 == 0) {
                            log.info("progress sessionId={} reads={} freePermits={}",
                                    sessionId, n, semaphore.availablePermits());
                        }
                    } finally {
                        semaphore.release();
                    }
                }));

                if (++counter % batchSize == 0) {
                    waitAll(batchFutures);
                    batchFutures.clear();
                }
            }

            waitAll(batchFutures);
            sessionRedis.expire("analysis:hits:" + sessionId, Duration.ofMinutes(40));

            if (!finalize) {
                releaseLock(lockKey);
                log.info("analyzeStream accumulated sessionId={} reads={}",
                        sessionId, processed.get());
                return ResponseEntity.ok(Map.of(
                        "status", "accumulated",
                        "sessionId", sessionId,
                        "readsProcessed", processed.get(),
                        "message", "data accumulated, waiting for finalize=true"
                ));
            }

            List<ReportResult> report = processingService.verifyCandidatesFromRedis(
                    sessionId, detProfile);

            releaseLock(lockKey);

            log.info("analyzeStream done sessionId={} reads={} hits={}",
                    sessionId, processed.get(), report.size());
            return ResponseEntity.ok(report);

        } catch (Exception e) {
            releaseLock(lockKey);
            throw unwrap(e);
        }
    }

    private void releaseLock(String lockKey) {
        if (lockKey != null) {
            try {
                sessionRedis.delete(lockKey);
            } catch (Exception e) {
                log.warn("failed to release lock {}", lockKey, e);
            }
        }
    }

    private static RuntimeException unwrap(Exception e) {
        Throwable t = e;
        if (t instanceof ExecutionException && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof RuntimeException re) {
            return re;
        }
        return new RuntimeException(t);
    }

    private void waitAll(List<Future<?>> futures) throws Exception {
        for (Future<?> f : futures) {
            f.get();
        }
    }

    private double averagePhred(String quality) {
        if (quality == null || quality.isEmpty()) return 0.0;
        long sum = 0;
        for (int i = 0; i < quality.length(); i++) {
            sum += quality.charAt(i) - 33;
        }
        return (double) sum / quality.length();
    }
}