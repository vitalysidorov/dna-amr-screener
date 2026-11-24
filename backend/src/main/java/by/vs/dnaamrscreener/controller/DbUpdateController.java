package by.vs.dnaamrscreener.controller;

import by.vs.dnaamrscreener.exception.FileAlreadyProcessingException;
import by.vs.dnaamrscreener.service.DbVersionService;
import by.vs.dnaamrscreener.service.DnaProcessingService;
import by.vs.dnaamrscreener.service.GeneParserService;
import by.vs.dnaamrscreener.service.KmerPrefixService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
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
    private final KmerPrefixService kmerPrefixService;

    @Value("${spring.application.seed-parallelism:16}")
    private int seedParallelism;

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

    public DbUpdateController(
            @Qualifier("sessionRedisTemplate") StringRedisTemplate sessionRedis,
            DbVersionService versionService,
            DnaProcessingService processingService,
            GeneParserService geneParserService, KmerPrefixService kmerPrefixService) {

        this.sessionRedis = sessionRedis;
        this.versionService = versionService;
        this.processingService = processingService;
        this.geneParserService = geneParserService;
        this.kmerPrefixService = kmerPrefixService;
    }

    @GetMapping("/status")
    public ResponseEntity<DbVersionService.DbStatus> status(
            @RequestParam(required = true) String kmerPrefix
    ) {
        if (kmerPrefixService.isIndexExists(kmerPrefix)) {
            return ResponseEntity.ok(versionService.status(kmerPrefix.toLowerCase()));
        } else {
            throw new IllegalArgumentException("invalid prefix");
        }
    }

    @PostMapping(value = "/seed", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<?> seedToInactive(
            @RequestParam(defaultValue = "k21") String kmerPrefix,
            @RequestHeader(value = "X-File-Hash", required = false) String fileHash,
            @RequestHeader(value = "X-LSH-Kmer-Size", required = false) Integer kmerSize,
            @RequestHeader(value = "X-LSH-Num-Hashes", required = false) Integer numHashes,
            @RequestHeader(value = "X-LSH-Bands", required = false) Integer bands,
            @RequestHeader(value = "X-LSH-Rows-Per-Band", required = false) Integer rowsPerBand,
            HttpServletRequest request) throws Exception {

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
        String targetSlot = versionService.getInactiveSlot(finalKmerPrefix);
        String dbPrefix = versionService.inactiveDataPrefix(finalKmerPrefix);

        try {
            long deleted = versionService.clearSlot(finalKmerPrefix, targetSlot);
            log.info("[DB] cleared inactive slot={} k-mer={} keys={}", targetSlot, finalKmerPrefix, deleted);

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
                                    dbPrefix, finalKmerPrefix,
                                    geneData[0], geneData[1], geneData[2],
                                    currentKmer, currentHashes, currentBands, currentRows);
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

    @PostMapping("/newIndex")
    public ResponseEntity<?> createNewIndex(@RequestParam(required = true) String kmerPrefix,
                                            @RequestHeader(value = "X-LSH-Kmer-Size") Integer kmerSize,
                                            @RequestHeader(value = "X-LSH-Num-Hashes") Integer numHashes,
                                            @RequestHeader(value = "X-LSH-Bands") Integer bands,
                                            @RequestHeader(value = "X-LSH-Rows-Per-Band") Integer rowsPerBand
    ) throws Exception {
        int currentKmer, currentHashes, currentBands, currentRows;

        final String finalKmerPrefix = kmerPrefix.toLowerCase();
        kmerPrefixService.requirePrefix(finalKmerPrefix);

        if (kmerPrefixService.isIndexExists(finalKmerPrefix)) {
            throw new IllegalArgumentException("prefix already exists");
        } else if (!kmerPrefixService.isIndexExists(finalKmerPrefix) && isBetween(kmerSize, 1, 32) &&
                isBetween(numHashes, 1, 1024)
                && bands >= 1 && rowsPerBand >= 1 && (bands * rowsPerBand == numHashes)) {
            currentKmer = kmerSize;
            currentHashes = numHashes;
            currentBands = bands;
            currentRows = rowsPerBand;
        } else {
            throw new IllegalArgumentException("invalid headers");
        }

        log.info("[CARD] начало импорта базы данных CARD...");
        long startTime = System.currentTimeMillis();

        ClassPathResource resource = new ClassPathResource("data/CARD.fasta");
        if (!resource.exists()) {
            log.error("[CARD] файл CARD.fasta не обнаружен");
            throw new RuntimeException("CARD.fasta not found");
        }

        versionService.setActiveSlot(finalKmerPrefix, DbVersionService.BLUE);
        final String currentDbPrefix = versionService.activeDataPrefix(finalKmerPrefix);

        List<String[]> geneBuffer = new ArrayList<>();

        try (InputStream inputStream = resource.getInputStream();
             BufferedReader reader = new BufferedReader(
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
                                currentDbPrefix,
                                finalKmerPrefix,
                                geneData[0],
                                geneData[1],
                                geneData[2],
                                currentKmer, currentHashes, currentBands, currentRows);
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        log.error("[CARD] failed to seed gene {}", geneData[0], e);
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
            log.error("[CARD] импорт завершён с ошибками: {} из {}", failed.get(), total);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "partial_failure",
                    "targetSlot", currentDbPrefix,
                    "genesLoaded", total - failed.get(),
                    "genesFailed", failed.get()
            ));
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("[CARD] проиндексировано генов: {}. Время: {} ms", total, duration);

        kmerPrefixService.addNewIndex(
                finalKmerPrefix, currentKmer, currentHashes, currentBands, currentRows);

        return ResponseEntity.ok(Map.of(
                "createdIndex", finalKmerPrefix,
                "totalCount", total,
                "duration", duration,
                "message", "created successfully"
        ));
    }

    @PostMapping("/switch")
    public ResponseEntity<?> switchSlot(
            @RequestParam(required = true) String kmerPrefix
    ) {
        if (kmerPrefixService.isIndexExists(kmerPrefix)) {
            String currentKmerPrefix = kmerPrefix.toLowerCase();
            String previous = versionService.getActiveSlot(currentKmerPrefix);
            String nowActive = versionService.switchActive(currentKmerPrefix);
            log.info("[DB] switched active {} -> {} with prefix: {}", previous, nowActive, currentKmerPrefix);
            return ResponseEntity.ok(Map.of(
                    "previousActive", previous,
                    "currentActive", nowActive,
                    "message", "switched successfully"
            ));
        } else {
            throw new IllegalArgumentException("invalid prefix");
        }
    }

    @DeleteMapping("/inactive")
    public ResponseEntity<?> clearInactive(
            @RequestParam(required = true) String kmerPrefix
    ) {
        if (kmerPrefixService.isIndexExists(kmerPrefix)) {
            String currentKmerPrefix = kmerPrefix.toLowerCase();
            String slot = versionService.getInactiveSlot(currentKmerPrefix);
            long deleted = versionService.clearSlot(currentKmerPrefix, slot);
            return ResponseEntity.ok(Map.of(
                    "clearedSlot", slot,
                    "deletedKeys", deleted
            ));
        } else {
            throw new IllegalArgumentException("invalid prefix");
        }
    }

    @PostMapping("/activate/{slot}")
    public ResponseEntity<?> activate(
            @PathVariable String slot,
            @RequestParam(required = true) String kmerPrefix
    ) {
        if (kmerPrefixService.isIndexExists(kmerPrefix)) {
            String currentKmerPrefix = kmerPrefix.toLowerCase();
            versionService.setActiveSlot(currentKmerPrefix, slot);
            return ResponseEntity.ok(Map.of(
                    "currentActive", versionService.getActiveSlot(currentKmerPrefix)
            ));
        } else {
            throw new IllegalArgumentException("invalid prefix");
        }
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

    private static boolean isBetween(int value, int min, int max) {
        return value >= min && value <= max;
    }
}