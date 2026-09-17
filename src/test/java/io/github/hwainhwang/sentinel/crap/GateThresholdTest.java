package io.github.hwainhwang.sentinel.crap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Mirrors the vendored SENTINEL_SPEC golden/gate/threshold-v1.json cases. */
class GateThresholdTest {
    @Test
    void defaultsAreCrapEightAndNinetyPercentKillRate() {
        assertEquals("8", GateThreshold.DEFAULT_CRAP_MAX.text());
        assertEquals("90", GateThreshold.DEFAULT_MUTATION_MIN.text());
        assertEquals(BigInteger.valueOf(8), GateThreshold.DEFAULT_CRAP_MAX.numerator());
        assertEquals(BigInteger.ONE, GateThreshold.DEFAULT_CRAP_MAX.denominator());
    }

    @Test
    void decimalTextIsReadAsAnExactReducedFraction() {
        GateThreshold half = GateThreshold.crapMax("8.50");
        assertEquals(BigInteger.valueOf(17), half.numerator());
        assertEquals(BigInteger.valueOf(2), half.denominator());
        GateThreshold percent = GateThreshold.mutationMin("75.01");
        assertEquals(BigInteger.valueOf(7501), percent.numerator());
        assertEquals(BigInteger.valueOf(100), percent.denominator());
    }

    @Test
    void rejectsMalformedAndOutOfRangeText() {
        for (String value : List.of("", "8.", ".5", "08", "8.000", "-1", "1e1", " 8", "8,5", "abc", "+8")) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> GateThreshold.crapMax(value), value);
            assertEquals("crapMaxInvalid", failure.getMessage());
        }
        assertEquals("crapMaxOutOfRange",
                assertThrows(IllegalArgumentException.class, () -> GateThreshold.crapMax("0")).getMessage());
        assertEquals("crapMaxOutOfRange",
                assertThrows(IllegalArgumentException.class, () -> GateThreshold.crapMax("0.00")).getMessage());
        assertEquals("mutationMinOutOfRange",
                assertThrows(IllegalArgumentException.class, () -> GateThreshold.mutationMin("100.01")).getMessage());
        assertEquals("mutationMinOutOfRange",
                assertThrows(IllegalArgumentException.class, () -> GateThreshold.mutationMin("101")).getMessage());
        assertEquals("mutationMinInvalid",
                assertThrows(IllegalArgumentException.class, () -> GateThreshold.mutationMin(null)).getMessage());
    }

    @Test
    void goldenCrapCasesPassOrFailAgainstTheGivenLimit() {
        assertTrue(ExactCrap.calculate(8, 1, 1, GateThreshold.crapMax("8")).passed());
        assertFalse(ExactCrap.calculate(9, 1, 1, GateThreshold.crapMax("8")).passed());
        assertTrue(ExactCrap.calculate(9, 1, 1, GateThreshold.crapMax("9")).passed());
        assertFalse(ExactCrap.calculate(4, 3, 4, GateThreshold.crapMax("4.24")).passed());
        assertTrue(ExactCrap.calculate(4, 3, 4, GateThreshold.crapMax("4.25")).passed());
        assertFalse(ExactCrap.calculate(2, 1, 2, GateThreshold.crapMax("2.49")).passed());
    }

    @Test
    void goldenMutationCasesFollowTheMinimumKillRate() {
        assertTrue(GateThreshold.DEFAULT_MUTATION_MIN.killRatePasses(9, 10));
        assertFalse(GateThreshold.DEFAULT_MUTATION_MIN.killRatePasses(8999, 10000));
        assertFalse(GateThreshold.mutationMin("100").killRatePasses(9, 10));
        assertTrue(GateThreshold.mutationMin("100").killRatePasses(4, 4));
        assertFalse(GateThreshold.mutationMin("100").killRatePasses(3, 4));
        assertTrue(GateThreshold.mutationMin("75").killRatePasses(3, 4));
        assertFalse(GateThreshold.mutationMin("75.01").killRatePasses(3, 4));
        assertTrue(GateThreshold.mutationMin("0").killRatePasses(0, 2));
    }
}
