package by.vs.dnaamrscreener.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;


class DnaProcessingServiceThresholdTest {

    private DnaProcessingService service;
    private Method isStrictHit;
    private Method isRelaxedHit;

    @BeforeEach
    void setUp() throws Exception {
        StringRedisTemplate geneRedis = mock(StringRedisTemplate.class);
        StringRedisTemplate sessionRedis = mock(StringRedisTemplate.class);
        DbVersionService versionService = mock(DbVersionService.class);
        KmerPrefixService kmerPrefixService = new KmerPrefixService(geneRedis);

        service = new DnaProcessingService(geneRedis, sessionRedis, versionService, kmerPrefixService);

        ReflectionTestUtils.setField(service, "strictPrimaryMinIdentity", 0.95);
        ReflectionTestUtils.setField(service, "strictPrimaryMinBreadth1x", 0.80);
        ReflectionTestUtils.setField(service, "strictPrimaryMinBreadth2x", 0.40);
        ReflectionTestUtils.setField(service, "strictPrimaryMinMeanDepth", 5.0);
        ReflectionTestUtils.setField(service, "strictSecondaryMinIdentity", 0.90);
        ReflectionTestUtils.setField(service, "strictSecondaryMinBreadth1x", 0.80);
        ReflectionTestUtils.setField(service, "strictSecondaryMinBreadth2x", 0.50);
        ReflectionTestUtils.setField(service, "strictSecondaryMinMeanDepth", 8.0);

        ReflectionTestUtils.setField(service, "relaxedPrimaryMinIdentity", 0.90);
        ReflectionTestUtils.setField(service, "relaxedPrimaryMinBreadth1x", 0.70);
        ReflectionTestUtils.setField(service, "relaxedPrimaryMinBreadth2x", 0.30);
        ReflectionTestUtils.setField(service, "relaxedPrimaryMinMeanDepth", 3.0);
        ReflectionTestUtils.setField(service, "relaxedSecondaryMinIdentity", 0.85);
        ReflectionTestUtils.setField(service, "relaxedSecondaryMinBreadth1x", 0.75);
        ReflectionTestUtils.setField(service, "relaxedSecondaryMinBreadth2x", 0.40);
        ReflectionTestUtils.setField(service, "relaxedSecondaryMinMeanDepth", 5.0);

        isStrictHit = DnaProcessingService.class.getDeclaredMethod(
                "isStrictHit", double.class, double.class, double.class, double.class);
        isStrictHit.setAccessible(true);
        isRelaxedHit = DnaProcessingService.class.getDeclaredMethod(
                "isRelaxedHit", double.class, double.class, double.class, double.class);
        isRelaxedHit.setAccessible(true);
    }

    private boolean invokeStrict(double id, double b1x, double b2x, double meanDepth) throws Exception {
        return (boolean) isStrictHit.invoke(service, id, b1x, b2x, meanDepth);
    }

    private boolean invokeRelaxed(double id, double b1x, double b2x, double meanDepth) throws Exception {
        return (boolean) isRelaxedHit.invoke(service, id, b1x, b2x, meanDepth);
    }

    @ParameterizedTest(name = "strict id={0} b1x={1} b2x={2} depth={3} → {4}")
    @CsvSource({
            "0.95, 0.80, 0.40, 1.0, true",
            "0.95, 0.80, 0.10, 5.0, true",
            "0.95, 0.80, 0.10, 4.0, false",
            "0.95, 0.79, 0.50, 10.0, false",

            "0.90, 0.80, 0.50, 1.0, true",
            "0.90, 0.80, 0.20, 8.0, true",
            "0.90, 0.80, 0.20, 7.0, false",
            "0.89, 0.99, 0.99, 20.0, false"
    })
    @DisplayName("isStrictHit")
    void strict_thresholds(double id, double b1x, double b2x, double depth, boolean expected) throws Exception {
        assertEquals(expected, invokeStrict(id, b1x, b2x, depth));
    }

    @ParameterizedTest(name = "relaxed id={0} b1x={1} b2x={2} depth={3} → {4}")
    @CsvSource({
            "0.90, 0.70, 0.30, 1.0, true",
            "0.90, 0.70, 0.10, 3.0, true",
            "0.90, 0.70, 0.10, 2.0, false",

            "0.85, 0.75, 0.40, 1.0, true",
            "0.85, 0.75, 0.10, 5.0, true",
            "0.85, 0.75, 0.10, 4.0, false",
            "0.84, 0.99, 0.99, 20.0, false"
    })
    @DisplayName("isRelaxedHit")
    void relaxed_thresholds(double id, double b1x, double b2x, double depth, boolean expected) throws Exception {
        assertEquals(expected, invokeRelaxed(id, b1x, b2x, depth));
    }
}
