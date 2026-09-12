package io.github.hwainhwang.sentinel.rendering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class CanonicalDecimalTest {
    @Test
    void rendersSpecGoldenFractionsWithoutFloatingPoint() {
        assertDecimal("17", "4", "4.25");
        assertDecimal("1", "3", "0.333333333333");
        assertDecimal("246913578025", "2000000000000", "0.123456789012");
        assertDecimal("246913578027", "2000000000000", "0.123456789014");
        assertDecimal("1999999999999", "2000000000000", "1");
        assertDecimal("0", "1", "0");
    }

    @Test
    void rejectsNegativeNumeratorAndNonpositiveDenominator() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalDecimal.render(BigInteger.valueOf(-1), BigInteger.ONE));
        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalDecimal.render(BigInteger.ONE, BigInteger.ZERO));
    }

    private static void assertDecimal(String numerator, String denominator, String expected) {
        assertEquals(
                expected,
                CanonicalDecimal.render(new BigInteger(numerator), new BigInteger(denominator)));
    }
}
