package by.vs.dnaamrscreener.controller;

import by.vs.dnaamrscreener.dto.ReportResult;
import by.vs.dnaamrscreener.exception.FileAlreadyProcessingException;
import by.vs.dnaamrscreener.service.DnaProcessingService;
import by.vs.dnaamrscreener.service.KmerPrefixService;
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
    private final KmerPrefixService kmerPrefixService;

    @Value("${spring.application.analyze-parallelism}")
    private int analyzeParallelism;
    @Value("${spring.application.batch-size}")
    private int batchSize;
    @Value("${screener.lsh.min-avg-phred}")
    private double minAvgPhred = 20.0;

    @Value("${screener.lsh.k21.kmer-size}")
    private Integer defKmerK21;
    @Value("${screener.lsh.k21.num-hashes}")
    private Integer defHashesK21;
    @Value("${screener.lsh.k21.bands}")
    private Integer defBandsK21;
    @Value("${screener.lsh.k21.rows-per-band}")
    private Integer defRowsK21;

    @Value("${screener.lsh.k17.kmer-size}")
    private Integer defKmerK17;
    @Value("${screener.lsh.k17.num-hashes}")
    private Integer defHashesK17;
    @Value("${screener.lsh.k17.bands}")
    private Integer defBandsK17;
    @Value("${screener.lsh.k17.rows-per-band}")
    private Integer defRowsK17;

    public DnaAnalysisController(@Qualifier("sessionRedisTemplate") StringRedisTemplate sessionRedis,
                                 DnaProcessingService processingService, KmerPrefixService kmerPrefixService) {
        this.sessionRedis = sessionRedis;
        this.processingService = processingService;
        this.kmerPrefixService = kmerPrefixService;
    }

    @PostMapping(value = "/analyze", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<?> analyzeStream(
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "k21") String kmerPrefix,
            @RequestParam(defaultValue = "strict") String profile,
            @RequestParam(defaultValue = "true") boolean finalize,

            @RequestHeader(value = "X-File-Hash", required = false) String fileHash,
            @RequestHeader(value = "X-LSH-Kmer-Size", required = false) Integer kmerSize,
            @RequestHeader(value = "X-LSH-Num-Hashes", required = false) Integer numHashes,
            @RequestHeader(value = "X-LSH-Bands", required = false) Integer bands,
            @RequestHeader(value = "X-LSH-Rows-Per-Band", required = false) Integer rowsPerBand,

            HttpServletRequest request) {

        int currentKmer, currentHashes, currentBands, currentRows;
        final String finalKmerPrefix = kmerPrefix.toLowerCase();

        if (finalKmerPrefix.equalsIgnoreCase("k21")) {
            currentKmer = defKmerK21;
            currentHashes = defHashesK21;
            currentBands = defBandsK21;
            currentRows = defRowsK21;
        } else if (finalKmerPrefix.equalsIgnoreCase("k17")) {
            currentKmer = defKmerK17;
            currentHashes = defHashesK17;
            currentBands = defBandsK17;
            currentRows = defRowsK17;
        } else if (kmerPrefixService.isIndexExists(finalKmerPrefix) && isBetween(kmerSize, 1, 32) &&
                isBetween(numHashes, 1, 1024)
                && bands >= 1 && rowsPerBand >= 1 && (bands * rowsPerBand == numHashes)) {
            currentKmer = kmerSize;
            currentHashes = numHashes;
            currentBands = bands;
            currentRows = rowsPerBand;
        } else {
            throw new IllegalArgumentException("invalid prefix or headers");
        }

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
                        processingService.queryLshAndIncrementDynamic(seq, sessionId, finalKmerPrefix, currentKmer,
                                currentHashes, currentBands, currentRows);

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
            sessionRedis.expire("analysis:{" + sessionId + "}:hits", Duration.ofMinutes(40));

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
                    sessionId, detProfile, finalKmerPrefix);
            report.sort(Comparator.comparingDouble(ReportResult::getMatchPercentage).reversed());

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

    private static boolean isBetween(int value, int min, int max) {
        return value >= min && value <= max;
    }
}