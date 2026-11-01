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

    @Value("${spring.application.analyze-parallelism:16}")
    private int analyzeParallelism;

    private final DnaProcessingService processingService;
    private final StringRedisTemplate geneRedis;
    private final GeneParserService geneParserService;
    private final DbVersionService versionService;

    @Value("${screener.lsh.kmer-size}")
    private Integer defKmer;
    @Value("${screener.lsh.num-hashes}")
    private Integer defHashes;
    @Value("${screener.lsh.bands}")
    private Integer defBands;
    @Value("${screener.lsh.rows-per-band}")
    private Integer defRows;

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
        if (Boolean.TRUE.equals(geneRedis.hasKey(INIT_KEY))) {
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

        versionService.setActiveSlot(DbVersionService.BLUE);
        final String prefix = versionService.activePrefix();

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
        Semaphore semaphore = new Semaphore(analyzeParallelism);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(total);

            for (String[] geneData : geneBuffer) {
                futures.add(executor.submit(() -> {
                    semaphore.acquireUninterruptibly();
                    try {
                        processingService.seedReferenceGeneWithPrefix(
                                prefix,
                                geneData[0],
                                geneData[1],
                                geneData[2],
                                defKmer, defHashes, defBands, defRows);
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

        geneRedis.opsForValue().set(INIT_KEY, "true");
        long duration = System.currentTimeMillis() - startTime;
        log.info("[CARD] проиндексировано генов: {}. Время: {} ms", total, duration);
    }
}