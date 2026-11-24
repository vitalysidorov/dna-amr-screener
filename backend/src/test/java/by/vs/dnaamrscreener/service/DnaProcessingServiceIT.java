package by.vs.dnaamrscreener.service;

import by.vs.dnaamrscreener.dto.ReportResult;
import by.vs.dnaamrscreener.support.RedisContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end path: seed reference gene → MinHash/LSH query → exact verify.
 * Requires Docker (Redis) and libdna_hasher on jna.library.path / LD_LIBRARY_PATH.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("by.vs.dnaamrscreener.support.RedisContainerSupport#dockerAvailable")
class DnaProcessingServiceIT {

    private static final int K = 15;
    private static final int HASHES = 100;
    private static final int BANDS = 20;
    private static final int ROWS = 5;

    // ~90bp synthetic gene
    private static final String GENE_SEQ =
            "ATGAAACGCATTAGCACCACCATTACCACCACCATCACCATTACCACAGGTAACGGTGCGGGCTGA"
                    + "ACGTACGTACGTACGTACGTACGTACGTACG";
    private static final String GENE_ID = "blaTEST-1";

    @Container
    static GenericContainer<?> redis = RedisContainerSupport.createRedisContainer();

    private StringRedisTemplate geneRedis;
    private StringRedisTemplate sessionRedis;
    private DbVersionService versionService;
    private DnaProcessingService processingService;

    @BeforeEach
    void setUp() {
        assumeNativeLibraryLoaded();

        geneRedis = RedisContainerSupport.createTemplate(redis);
        sessionRedis = RedisContainerSupport.createTemplate(redis);
        geneRedis.getConnectionFactory().getConnection().serverCommands().flushAll();

        versionService = new DbVersionService(geneRedis);
        versionService.setActiveSlot(DbVersionService.BLUE);

        processingService = new DnaProcessingService(geneRedis, sessionRedis, versionService);
        ReflectionTestUtils.setField(processingService, "sessionTtlSeconds", 600L);
        ReflectionTestUtils.setField(processingService, "maxReadsPerGene", 80);
    }

    @Test
    void seed_and_detect_identical_read_strict() {
        String prefix = versionService.activePrefix();
        processingService.seedReferenceGeneWithPrefix(
                prefix, GENE_ID, GENE_SEQ, "beta-lactams", K, HASHES, BANDS, ROWS);

        String sessionId = UUID.randomUUID().toString();
        // identical sequence should hit LSH buckets and pass strict thresholds
        processingService.queryLshAndIncrementDynamic(GENE_SEQ, sessionId, K, HASHES, BANDS, ROWS);

        List<ReportResult> report = processingService.verifyCandidatesFromRedis(
                sessionId, DnaProcessingService.DetectionProfile.STRICT);

        assertFalse(report.isEmpty(), "expected at least one hit for identical gene sequence");
        ReportResult hit = report.stream()
                .filter(r -> GENE_ID.equals(r.getGeneId()))
                .findFirst()
                .orElseThrow();
        assertEquals("beta-lactams", hit.getAntibiotic());
        assertTrue(hit.getMatchPercentage() >= 90.0, "identity should be high, got " + hit.getMatchPercentage());
    }

    @Test
    void unrelated_sequence_produces_no_strict_hit() {
        String prefix = versionService.activePrefix();
        processingService.seedReferenceGeneWithPrefix(
                prefix, GENE_ID, GENE_SEQ, "beta-lactams", K, HASHES, BANDS, ROWS);

        String sessionId = UUID.randomUUID().toString();
        String unrelated = "GGGGCCCCGGGGCCCCGGGGCCCCGGGGCCCCGGGGCCCCGGGGCCCCGGGGCCCCGGGGCCCCGGGGCCCC";
        processingService.queryLshAndIncrementDynamic(unrelated, sessionId, K, HASHES, BANDS, ROWS);

        List<ReportResult> report = processingService.verifyCandidatesFromRedis(
                sessionId, DnaProcessingService.DetectionProfile.STRICT);

        assertTrue(report.stream().noneMatch(r -> GENE_ID.equals(r.getGeneId())),
                "unrelated read must not produce a strict hit");
    }

    private static void assumeNativeLibraryLoaded() {
        try {
            // touch the JNA interface — fails fast if .so is missing
            Class.forName("by.vs.dnaamrscreener.jna.DNAHasherLib");
            by.vs.dnaamrscreener.jna.DNAHasherLib.INSTANCE.getClass();
        } catch (Throwable t) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "libdna_hasher not available: " + t.getMessage());
        }
    }
}
