package io.github.hwainhwang.sentinel.crap;

import io.github.hwainhwang.sentinel.rendering.CanonicalDecimal;
import java.math.BigInteger;

/** An exact CRAP score represented as a reduced fraction. */
public final class ExactCrap {
    private final BigInteger numerator;
    private final BigInteger denominator;
    private final String decimal;
    private final boolean passed;

    private ExactCrap(BigInteger numerator, BigInteger denominator, boolean passed) {
        this.numerator = numerator;
        this.denominator = denominator;
        this.decimal = CanonicalDecimal.render(numerator, denominator);
        this.passed = passed;
    }

    public static ExactCrap calculate(long complexity, long coveredUnits, long totalUnits) {
        return calculate(complexity, coveredUnits, totalUnits, GateThreshold.DEFAULT_CRAP_MAX);
    }

    public static ExactCrap calculate(
            long complexity, long coveredUnits, long totalUnits, GateThreshold crapMax) {
        validate(complexity, coveredUnits, totalUnits);
        if (crapMax == null) {
            throw new IllegalArgumentException("crapMaxInvalid");
        }
        BigInteger cc = BigInteger.valueOf(complexity);
        BigInteger total = BigInteger.valueOf(totalUnits);
        BigInteger uncovered = BigInteger.valueOf(totalUnits - coveredUnits);
        BigInteger denominator = total.pow(3);
        BigInteger numerator = cc.pow(2).multiply(uncovered.pow(3)).add(cc.multiply(denominator));
        boolean passed = crapMax.crapPasses(numerator, denominator);
        BigInteger divisor = numerator.gcd(denominator);
        return new ExactCrap(numerator.divide(divisor), denominator.divide(divisor), passed);
    }

    private static void validate(long complexity, long coveredUnits, long totalUnits) {
        if (complexity < 1 || complexity > Models.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("cyclomaticComplexityOutOfRange");
        }
        if (coveredUnits < 0 || coveredUnits > Models.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("coveredUnitsOutOfRange");
        }
        if (totalUnits < 1 || totalUnits > Models.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("totalUnitsOutOfRange");
        }
        if (coveredUnits > totalUnits) {
            throw new IllegalArgumentException("coveredUnitsExceedTotalUnits");
        }
    }

    public BigInteger numerator() {
        return numerator;
    }

    public BigInteger denominator() {
        return denominator;
    }

    public String decimal() {
        return decimal;
    }

    public boolean passed() {
        return passed;
    }
}
