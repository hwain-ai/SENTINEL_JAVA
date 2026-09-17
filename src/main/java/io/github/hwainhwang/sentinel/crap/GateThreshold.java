package io.github.hwainhwang.sentinel.crap;

import java.math.BigInteger;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A gate threshold (CRAP upper bound or minimum mutation kill rate) as exact decimal text.
 *
 * <p>The text contract is shared with SENTINEL_SPEC golden/gate/threshold-v1.json: a decimal
 * string with at most two fractional places, read as an exact reduced fraction.
 */
public record GateThreshold(String text, BigInteger numerator, BigInteger denominator) {
    private static final Pattern TEXT = Pattern.compile("^(0|[1-9][0-9]*)(\\.[0-9]{1,2})?$");
    private static final BigInteger HUNDRED = BigInteger.valueOf(100);
    public static final GateThreshold DEFAULT_CRAP_MAX = crapMax("8");
    public static final GateThreshold DEFAULT_MUTATION_MIN = mutationMin("90");

    public GateThreshold {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(numerator, "numerator");
        Objects.requireNonNull(denominator, "denominator");
    }

    public static GateThreshold crapMax(String text) {
        GateThreshold value = parse(text, "crapMax");
        if (value.numerator.signum() == 0) {
            throw new IllegalArgumentException("crapMaxOutOfRange");
        }
        return value;
    }

    public static GateThreshold mutationMin(String text) {
        GateThreshold value = parse(text, "mutationMin");
        if (value.numerator.compareTo(value.denominator.multiply(HUNDRED)) > 0) {
            throw new IllegalArgumentException("mutationMinOutOfRange");
        }
        return value;
    }

    private static GateThreshold parse(String text, String field) {
        if (text == null || !TEXT.matcher(text).matches()) {
            throw new IllegalArgumentException(field + "Invalid");
        }
        int dot = text.indexOf('.');
        String digits = dot < 0 ? text : text.substring(0, dot) + text.substring(dot + 1);
        int places = dot < 0 ? 0 : text.length() - dot - 1;
        BigInteger numerator = new BigInteger(digits);
        BigInteger denominator = BigInteger.TEN.pow(places);
        BigInteger divisor = numerator.gcd(denominator);
        return new GateThreshold(text, numerator.divide(divisor), denominator.divide(divisor));
    }

    /** Whether an exact CRAP fraction is at most this bound. */
    public boolean crapPasses(BigInteger crapNumerator, BigInteger crapDenominator) {
        return crapNumerator.multiply(denominator)
                .compareTo(numerator.multiply(crapDenominator)) <= 0;
    }

    /** Whether killed/inScope is at least this percentage; 100 means killed == inScope. */
    public boolean killRatePasses(long killed, long inScope) {
        return BigInteger.valueOf(killed).multiply(HUNDRED).multiply(denominator)
                .compareTo(numerator.multiply(BigInteger.valueOf(inScope))) >= 0;
    }
}
