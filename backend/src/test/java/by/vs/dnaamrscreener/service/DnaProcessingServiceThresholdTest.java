package by.vs.dnaamrscreener.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Thresholds are private static helpers inside DnaProcessingService.
 * We exercise them via reflection so production visibility stays unchanged.
 */
class DnaProcessingServiceThresholdTest {

    private static boolean invokeStrict(double id, double b1x, double b2x, double meanDepth) throws Exception {
        Method m = DnaProcessingService.class.getDeclaredMethod(
                "isStrictHit", double.class, double.class, double.class, double.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, id, b1x, b2x, meanDepth);
    }

    private static boolean invokeRelaxed(double id, double b1x, double b2x, double meanDepth) throws Exception {
        Method m = DnaProcessingService.class.getDeclaredMethod(
                "isRelaxedHit", double.class, double.class, double.class, double.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, id, b1x, b2x, meanDepth);
    }

    @ParameterizedTest(name = "strict id={0} b1x={1} b2x={2} depth={3} → {4}")
    @CsvSource({
            // high identity path: id>=0.95, b1x>=0.80, (b2x>=0.40 || depth>=5)
            "0.95, 0.80, 0.40, 1.0, true",
            "0.95, 0.80, 0.10, 5.0, true",
            "0.95, 0.80, 0.10, 4.0, false",
            "0.95, 0.79, 0.50, 10.0, false",
            // secondary path: id>=0.90, b1x>=0.80, (b2x>=0.50 || depth>=8)
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
            // id>=0.90, b1x>=0.70, (b2x>=0.30 || depth>=3)
            "0.90, 0.70, 0.30, 1.0, true",
            "0.90, 0.70, 0.10, 3.0, true",
            "0.90, 0.70, 0.10, 2.0, false",
            // secondary: id>=0.85, b1x>=0.75, (b2x>=0.40 || depth>=5)
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
