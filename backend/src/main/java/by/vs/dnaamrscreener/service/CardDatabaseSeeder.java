package by.vs.dnaamrscreener.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class CardDatabaseSeeder implements CommandLineRunner {

    private static final String INIT_KEY = "system:card:initialized";
    private static final String K21_KEY = "k21";
    private static final String K17_KEY = "k17";


    @Value("${spring.application.seed-parallelism}")
    private int seedParallelism;

    private final DnaProcessingService processingService;
    private final StringRedisTemplate geneRedis;
    private final GeneParserService geneParserService;
    private final DbVersionService versionService;

    @Value("${screener.lsh.k21.kmer-size}")
    private Integer defStrictKmer;
    @Value("${screener.lsh.k21.num-hashes}")
    private Integer defStrictHashes;
    @Value("${screener.lsh.k21.bands}")
    private Integer defStrictBands;
    @Value("${screener.lsh.k21.rows-per-band}")
    private Integer defStrictRows;

    @Value("${screener.lsh.k17.kmer-size}")
    private Integer defRelaxedKmer;
    @Value("${screener.lsh.k17.num-hashes}")
    private Integer defRelaxedHashes;
    @Value("${screener.lsh.k17.bands}")
    private Integer defRelaxedBands;
    @Value("${screener.lsh.k17.rows-per-band}")
    private Integer defRelaxedRows;

    public CardDatabaseSeeder(@Qualifier("geneRedisTemplate") StringRedisTemplate geneRedis,
                              DnaProcessingService processingService,
                              GeneParserService geneParserService,
                              DbVersionService versionService) {

        this.processingService = processingService;
        this.geneRedis = geneRedis;
        this.geneParserService = geneParserService;
        this.versionService = versionService;
    }

    @Override
    public void run(String... args) throws Exception {
        if (Boolean.TRUE.equals(geneRedis.hasKey(INIT_KEY + K21_KEY)) &&
                Boolean.TRUE.equals(geneRedis.hasKey(INIT_KEY + K17_KEY))) {
            log.info("[CARD] база данных CARD уже проиндексирована");
            return;
        }

        log.info("[CARD] начало импорта базы данных CARD...");
        long startTime = System.currentTimeMillis();

        ClassPathResource resource = new ClassPathResource("data/CARD.fasta");
        if (!resource.exists()) {
            log.error("[CARD] файл CARD.fasta не обнаружен");
            return;
        }

        versionService.setActiveSlot(K21_KEY, DbVersionService.BLUE);
        versionService.setActiveSlot(K17_KEY, DbVersionService.BLUE);

        final String prefixForStrict = versionService.activeDataPrefix(K21_KEY);
        final String prefixForRelaxed = versionService.activeDataPrefix(K17_KEY);

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
                                prefixForStrict,
                                K21_KEY,
                                geneData[0],
                                geneData[1],
                                geneData[2],
                                defStrictKmer, defStrictHashes, defStrictBands, defStrictRows);
                        processingService.seedReferenceGeneWithPrefix(
                                prefixForRelaxed,
                                K17_KEY,
                                geneData[0],
                                geneData[1],
                                geneData[2],
                                defRelaxedKmer, defRelaxedHashes, defRelaxedBands, defRelaxedRows);
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
            return;
        }

        geneRedis.opsForValue().set(INIT_KEY + K21_KEY, "true");
        geneRedis.opsForValue().set(INIT_KEY + K17_KEY, "true");
        long duration = System.currentTimeMillis() - startTime;
        log.info("[CARD] проиндексировано генов: {}. Время: {} ms", total, duration);
    }
}