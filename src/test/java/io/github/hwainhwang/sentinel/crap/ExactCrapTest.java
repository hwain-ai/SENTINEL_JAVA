package io.github.hwainhwang.sentinel.crap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class ExactCrapTest {
    @Test
    void matchesEverySpecFormulaGoldenCase() {
        assertValue(4, 4, 4, "4", "1", "4", true);
        assertValue(2, 1, 2, "5", "2", "2.5", true);
        assertValue(3, 0, 7, "12", "1", "12", false);
        assertValue(4, 3, 4, "17", "4", "4.25", true);
        assertValue(8, 1, 1, "8", "1", "8", true);
        assertValue(9, 1, 1, "9", "1", "9", false);
        assertValue(
                Models.MAX_SAFE_INTEGER,
                Models.MAX_SAFE_INTEGER,
                Models.MAX_SAFE_INTEGER,
                "9007199254740991",
                "1",
                "9007199254740991",
                false);
    }

    @Test
    void usesBigIntegerForLargeExactInputs() {
        long maximum = Models.MAX_SAFE_INTEGER;

        ExactCrap result = ExactCrap.calculate(maximum, maximum - 1, maximum);

        assertTrue(result.numerator().bitLength() > 63);
        assertTrue(result.denominator().signum() > 0);
    }

    @Test
    void rejectsImpossibleCounts() {
        assertThrows(IllegalArgumentException.class, () -> ExactCrap.calculate(0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> ExactCrap.calculate(1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> ExactCrap.calculate(1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ExactCrap.calculate(1, 2, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> ExactCrap.calculate(Models.MAX_SAFE_INTEGER + 1, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> ExactCrap.calculate(1, Models.MAX_SAFE_INTEGER + 1, Models.MAX_SAFE_INTEGER));
        assertThrows(
                IllegalArgumentException.class,
                () -> ExactCrap.calculate(1, 0, Models.MAX_SAFE_INTEGER + 1));
    }

    @Test
    void exactEightPassesAndTheNextIntegerFails() {
        assertTrue(ExactCrap.calculate(8, 1, 1).passed());
        assertFalse(ExactCrap.calculate(9, 1, 1).passed());
    }

    @Test
    void exhaustiveSmallDomainMatchesTheIndependentIntegerFormula() {
        for (long complexity = 1; complexity <= 50; complexity++) {
            for (long total = 1; total <= 50; total++) {
                for (long covered = 0; covered <= total; covered++) {
                    ExactCrap actual = ExactCrap.calculate(complexity, covered, total);
                    BigInteger cc = BigInteger.valueOf(complexity);
                    BigInteger denominator = BigInteger.valueOf(total).pow(3);
                    BigInteger uncovered = BigInteger.valueOf(total - covered);
                    BigInteger numerator = cc.pow(2)
                            .multiply(uncovered.pow(3))
                            .add(cc.multiply(denominator));
                    BigInteger divisor = numerator.gcd(denominator);

                    assertEquals(numerator.divide(divisor), actual.numerator());
                    assertEquals(denominator.divide(divisor), actual.denominator());
                    assertEquals(
                            numerator.compareTo(denominator.multiply(BigInteger.valueOf(8))) <= 0,
                            actual.passed());
                }
            }
        }
    }

    private static void assertValue(
            long complexity,
            long covered,
            long total,
            String numerator,
            String denominator,
            String decimal,
            boolean passed) {
        ExactCrap result = ExactCrap.calculate(complexity, covered, total);
        assertEquals(new BigInteger(numerator), result.numerator());
        assertEquals(new BigInteger(denominator), result.denominator());
        assertEquals(decimal, result.decimal());
        assertEquals(passed, result.passed());
    }
}
