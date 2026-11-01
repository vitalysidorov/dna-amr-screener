package by.vs.dnaamrscreener.controller;

import by.vs.dnaamrscreener.exception.FileAlreadyProcessingException;
import by.vs.dnaamrscreener.service.DbVersionService;
import by.vs.dnaamrscreener.service.DnaProcessingService;
import by.vs.dnaamrscreener.service.GeneParserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@RestController
@RequestMapping("/api/card")
public class DbUpdateController {

    private final StringRedisTemplate sessionRedis;
    private final DbVersionService versionService;
    private final DnaProcessingService processingService;
    private final GeneParserService geneParserService;

    @Value("${spring.application.seed-parallelism:16}")
    private int seedParallelism;

    @Value("${screener.lsh.kmer-size}")
    private Integer defKmer;
    @Value("${screener.lsh.num-hashes}")
    private Integer defHashes;
    @Value("${screener.lsh.bands}")
    private Integer defBands;
    @Value("${screener.lsh.rows-per-band}")
    private Integer defRows;

    public DbUpdateController(
            @Qualifier("sessionRedisTemplate") StringRedisTemplate sessionRedis,
            DbVersionService versionService,
            DnaProcessingService processingService,
            GeneParserService geneParserService) {

        this.sessionRedis = sessionRedis;
        this.versionService = versionService;
        this.processingService = processingService;
        this.geneParserService = geneParserService;
    }

    @GetMapping("/status")
    public ResponseEntity<DbVersionService.DbStatus> status() {
        return ResponseEntity.ok(versionService.status());
    }

    @PostMapping(value = "/seed", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<?> seedToInactive(
            @RequestHeader(value = "X-File-Hash", required = false) String fileHash,
            @RequestHeader(value = "X-LSH-Kmer-Size", required = false) Integer kmerSize,
            @RequestHeader(value = "X-LSH-Num-Hashes", required = false) Integer numHashes,
            @RequestHeader(value = "X-LSH-Bands", required = false) Integer bands,
            @RequestHeader(value = "X-LSH-Rows-Per-Band", required = false) Integer rowsPerBand,
            HttpServletRequest request) throws Exception {

        final int finalKmer = (kmerSize != null) ? kmerSize : defKmer;
        final int finalHashes = (numHashes != null) ? numHashes : defHashes;
        final int finalBands = (bands != null) ? bands : defBands;
        final int finalRows = (rowsPerBand != null) ? rowsPerBand : defRows;

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

        long start = System.currentTimeMillis();
        String targetSlot = versionService.getInactiveSlot();
        String prefix = versionService.inactivePrefix();

        try {
            long deleted = versionService.clearSlot(targetSlot);
            log.info("[DB] cleared inactive slot={} keys={}", targetSlot, deleted);

            List<String[]> geneBuffer = parseFasta(request.getInputStream());
            int total = geneBuffer.size();
            AtomicInteger failed = new AtomicInteger();
            Semaphore semaphore = new Semaphore(seedParallelism);

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> futures = new ArrayList<>(total);

                for (String[] geneData : geneBuffer) {
                    futures.add(executor.submit(() -> {
                        semaphore.acquireUninterruptibly();
                        try {
                            processingService.seedReferenceGeneWithPrefix(
                                    prefix,
                                    geneData[0], geneData[1], geneData[2],
                                    finalKmer, finalHashes, finalBands, finalRows);
                        } catch (Exception e) {
                            failed.incrementAndGet();
                            log.error("[DB] seed failed gene={}", geneData[0], e);
                        } finally {
                            semaphore.release();
                        }
                    }));
                }

                for (Future<?> f : futures) {
                    f.get();
                }
            }

            if (failed.get() > 0) {
                log.error("[DB] seed finished with errors: {}/{}", failed.get(), total);
                return ResponseEntity.internalServerError().body(Map.of(
                        "status", "partial_failure",
                        "targetSlot", targetSlot,
                        "genesLoaded", total - failed.get(),
                        "genesFailed", failed.get(),
                        "clearedKeys", deleted
                ));
            }

            long duration = System.currentTimeMillis() - start;
            log.info("[DB] seeded {} genes to slot={} in {} ms", total, targetSlot, duration);

            return ResponseEntity.ok(Map.of(
                    "status", "seeded",
                    "targetSlot", targetSlot,
                    "genesLoaded", total,
                    "clearedKeys", deleted,
                    "durationMs", duration,
                    "message", "new version loaded to inactive slot. call POST /api/db/switch to activate."
            ));

        } finally {
            if (lockKey != null) {
                try {
                    sessionRedis.delete(lockKey);
                } catch (Exception e) {
                    log.warn("failed to release lock {}", lockKey, e);
                }
            }
        }
    }

    @PostMapping("/switch")
    public ResponseEntity<?> switchSlot() {
        String previous = versionService.getActiveSlot();
        String nowActive = versionService.switchActive();
        log.info("[DB] switched active {} -> {}", previous, nowActive);
        return ResponseEntity.ok(Map.of(
                "previousActive", previous,
                "currentActive", nowActive,
                "message", "switched successfully"
        ));
    }

    @DeleteMapping("/inactive")
    public ResponseEntity<?> clearInactive() {
        String slot = versionService.getInactiveSlot();
        long deleted = versionService.clearSlot(slot);
        return ResponseEntity.ok(Map.of(
                "clearedSlot", slot,
                "deletedKeys", deleted
        ));
    }

    @PostMapping("/activate/{slot}")
    public ResponseEntity<?> activate(@PathVariable String slot) {
        versionService.setActiveSlot(slot);
        return ResponseEntity.ok(Map.of(
                "currentActive", versionService.getActiveSlot()
        ));
    }

    private List<String[]> parseFasta(InputStream inputStream) throws Exception {
        List<String[]> geneBuffer = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line;
            String currentGeneId = null;
            String currentAntibiotic = null;
            StringBuilder currentSequence = new StringBuilder();

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith(">")) {
                    if (currentGeneId != null && !currentSequence.isEmpty()) {
                        geneBuffer.add(new String[]{
                                currentGeneId,
                                currentSequence.toString(),
                                currentAntibiotic
                        });
                    }
                    String header = line.substring(1);
                    currentGeneId = geneParserService.parseGeneName(header);
                    currentAntibiotic = geneParserService.determineAntibioticClass(currentGeneId);
                    currentSequence.setLength(0);
                } else {
                    currentSequence.append(line.toUpperCase());
                }
            }
            if (currentGeneId != null && !currentSequence.isEmpty()) {
                geneBuffer.add(new String[]{
                        currentGeneId,
                        currentSequence.toString(),
                        currentAntibiotic
                });
            }
        }
        return geneBuffer;
    }
}