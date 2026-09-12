package io.github.hwainhwang.sentinel.rendering;

import java.math.BigInteger;

/** Renders non-negative rational numbers with the shared SENTINEL decimal contract. */
public final class CanonicalDecimal {
    private static final int SCALE = 12;
    private static final BigInteger FACTOR = BigInteger.TEN.pow(SCALE);

    private CanonicalDecimal() {
        throw new AssertionError("no instances");
    }

    public static String render(BigInteger numerator, BigInteger denominator) {
        requireFraction(numerator, denominator);
        BigInteger[] division = numerator.multiply(FACTOR).divideAndRemainder(denominator);
        BigInteger rounded = roundHalfEven(division[0], division[1], denominator);
        return format(rounded);
    }

    private static void requireFraction(BigInteger numerator, BigInteger denominator) {
        if (numerator == null || numerator.signum() < 0) {
            throw new IllegalArgumentException("numeratorOutOfRange");
        }
        if (denominator == null || denominator.signum() <= 0) {
            throw new IllegalArgumentException("denominatorOutOfRange");
        }
    }

    private static BigInteger roundHalfEven(
            BigInteger quotient, BigInteger remainder, BigInteger denominator) {
        int comparison = remainder.shiftLeft(1).compareTo(denominator);
        if (comparison > 0 || (comparison == 0 && quotient.testBit(0))) {
            return quotient.add(BigInteger.ONE);
        }
        return quotient;
    }

    private static String format(BigInteger scaled) {
        BigInteger[] parts = scaled.divideAndRemainder(FACTOR);
        if (parts[1].signum() == 0) {
            return parts[0].toString();
        }
        String digits = parts[1].toString();
        String fraction = "0".repeat(SCALE - digits.length()) + digits;
        while (fraction.endsWith("0")) {
            fraction = fraction.substring(0, fraction.length() - 1);
        }
        return parts[0] + "." + fraction;
    }
}
