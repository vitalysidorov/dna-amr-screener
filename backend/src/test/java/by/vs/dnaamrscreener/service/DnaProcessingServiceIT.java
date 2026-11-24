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

@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("by.vs.dnaamrscreener.support.RedisContainerSupport#dockerAvailable")
class DnaProcessingServiceIT {

    private static final String KMER_PREFIX = "k21";
    private static final int K = 21;
    private static final int HASHES = 100;
    private static final int BANDS = 25;
    private static final int ROWS = 4;

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

        KmerPrefixService kmerPrefixService = new KmerPrefixService(geneRedis);
        versionService = new DbVersionService(geneRedis, kmerPrefixService);
        versionService.setActiveSlot(KMER_PREFIX, DbVersionService.BLUE);

        processingService = new DnaProcessingService(
                geneRedis, sessionRedis, versionService, kmerPrefixService);
        ReflectionTestUtils.setField(processingService, "sessionTtlSeconds", 600L);
        ReflectionTestUtils.setField(processingService, "maxReadsPerGene", 80);
        ReflectionTestUtils.setField(processingService, "numberOfCandidates", 149L);

        ReflectionTestUtils.setField(processingService, "strictPrimaryMinIdentity", 0.95);
        ReflectionTestUtils.setField(processingService, "strictPrimaryMinBreadth1x", 0.80);
        ReflectionTestUtils.setField(processingService, "strictPrimaryMinBreadth2x", 0.40);
        ReflectionTestUtils.setField(processingService, "strictPrimaryMinMeanDepth", 5.0);
        ReflectionTestUtils.setField(processingService, "strictSecondaryMinIdentity", 0.90);
        ReflectionTestUtils.setField(processingService, "strictSecondaryMinBreadth1x", 0.80);
        ReflectionTestUtils.setField(processingService, "strictSecondaryMinBreadth2x", 0.50);
        ReflectionTestUtils.setField(processingService, "strictSecondaryMinMeanDepth", 8.0);

        ReflectionTestUtils.setField(processingService, "relaxedPrimaryMinIdentity", 0.90);
        ReflectionTestUtils.setField(processingService, "relaxedPrimaryMinBreadth1x", 0.70);
        ReflectionTestUtils.setField(processingService, "relaxedPrimaryMinBreadth2x", 0.30);
        ReflectionTestUtils.setField(processingService, "relaxedPrimaryMinMeanDepth", 3.0);
        ReflectionTestUtils.setField(processingService, "relaxedSecondaryMinIdentity", 0.85);
        ReflectionTestUtils.setField(processingService, "relaxedSecondaryMinBreadth1x", 0.75);
        ReflectionTestUtils.setField(processingService, "relaxedSecondaryMinBreadth2x", 0.40);
        ReflectionTestUtils.setField(processingService, "relaxedSecondaryMinMeanDepth", 5.0);
    }

    @Test
    void seed_and_detect_identical_read_strict() {
        String versionPrefix = versionService.activeDataPrefix(KMER_PREFIX);
        processingService.seedReferenceGeneWithPrefix(
                versionPrefix, KMER_PREFIX, GENE_ID, GENE_SEQ, "beta-lactams",
                K, HASHES, BANDS, ROWS);

        String sessionId = UUID.randomUUID().toString();
        processingService.queryLshAndIncrementDynamic(
                GENE_SEQ, sessionId, KMER_PREFIX, K, HASHES, BANDS, ROWS);

        List<ReportResult> report = processingService.verifyCandidatesFromRedis(
                sessionId, DnaProcessingService.DetectionProfile.STRICT, KMER_PREFIX);

        assertFalse(report.isEmpty(), "expected at least one hit for identical gene sequence");
        ReportResult hit = report.stream()
                .filter(r -> GENE_ID.equals(r.getGeneId()))
                .findFirst()
                .orElseThrow();
        assertEquals("beta-lactams", hit.getAntibiotic());
        assertTrue(hit.getMatchPercentage() >= 90.0,
                "identity should be high, got " + hit.getMatchPercentage());
    }

    @Test
    void unrelated_sequence_produces_no_strict_hit() {
        String versionPrefix = versionService.activeDataPrefix(KMER_PREFIX);
        processingService.seedReferenceGeneWithPrefix(
                versionPrefix, KMER_PREFIX, GENE_ID, GENE_SEQ, "beta-lactams",
                K, HASHES, BANDS, ROWS);

        String sessionId = UUID.randomUUID().toString();
        String unrelated = "GGGGCCCCGGGGCCCCGGGGCCCCGGGGCCCCGGGGCCCCGGGGCCCCGGGGCCCCGGGGCCCCGGGGCCCC";
        processingService.queryLshAndIncrementDynamic(
                unrelated, sessionId, KMER_PREFIX, K, HASHES, BANDS, ROWS);

        List<ReportResult> report = processingService.verifyCandidatesFromRedis(
                sessionId, DnaProcessingService.DetectionProfile.STRICT, KMER_PREFIX);

        assertTrue(report.stream().noneMatch(r -> GENE_ID.equals(r.getGeneId())),
                "unrelated read must not produce a strict hit");
    }

    private static void assumeNativeLibraryLoaded() {
        try {
            Class.forName("by.vs.dnaamrscreener.jna.DNAHasherLib");
            by.vs.dnaamrscreener.jna.DNAHasherLib.INSTANCE.getClass();
        } catch (Throwable t) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "libdna_hasher not available: " + t.getMessage());
        }
    }
}
