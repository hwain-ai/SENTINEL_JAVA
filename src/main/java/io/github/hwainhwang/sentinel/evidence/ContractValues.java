package io.github.hwainhwang.sentinel.evidence;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Small fail-closed type and range checks used by the evidence records. */
final class ContractValues {
    private static final Pattern HEX_256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern POSITIVE_DECIMAL = Pattern.compile("^[1-9][0-9]*$");
    private static final Pattern NONNEGATIVE_DECIMAL = Pattern.compile("^(?:0|[1-9][0-9]*)$");
    private static final BigInteger MAX_UINT64 = new BigInteger("18446744073709551615");
    private static final Set<Class<?>> INTEGER_TYPES = Set.of(
            Byte.class, Short.class, Integer.class, Long.class);

    private ContractValues() {
        throw new AssertionError("no instances");
    }

    static Map<String, Object> map(Object value, String code) {
        if (!(value instanceof Map<?, ?> source)) {
            throw new EvidenceContractException(code);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String name)) {
                throw new EvidenceContractException(code);
            }
            result.put(name, entry.getValue());
        }
        return result;
    }

    static List<?> list(Object value, String code) {
        if (!(value instanceof List<?> result)) {
            throw new EvidenceContractException(code);
        }
        return result;
    }

    static void exactFields(Map<String, Object> value, Set<String> expected, String code) {
        if (!value.keySet().equals(expected)) {
            throw new EvidenceContractException(code);
        }
    }

    static String string(Object value, String code) {
        if (!(value instanceof String result)) {
            throw new EvidenceContractException(code);
        }
        return result;
    }

    static boolean bool(Object value, String code) {
        if (!(value instanceof Boolean result)) {
            throw new EvidenceContractException(code);
        }
        return result;
    }

    static long safeInteger(Object value, boolean positive, String code) {
        if (value == null || !INTEGER_TYPES.contains(value.getClass())) {
            throw new EvidenceContractException(code);
        }
        long result = ((Number) value).longValue();
        if (result < (positive ? 1 : 0) || result > CanonicalJson.MAX_SAFE_INTEGER) {
            throw new EvidenceContractException(code);
        }
        return result;
    }

    static String hex256(Object value, String code) {
        String result = string(value, code);
        if (!HEX_256.matcher(result).matches()) {
            throw new EvidenceContractException(code);
        }
        return result;
    }

    static BigInteger uint64(Object value, boolean allowZero, String code) {
        String text = string(value, code);
        Pattern pattern = allowZero ? NONNEGATIVE_DECIMAL : POSITIVE_DECIMAL;
        if (!pattern.matcher(text).matches()) {
            throw new EvidenceContractException(code);
        }
        BigInteger result = new BigInteger(text);
        if (result.compareTo(MAX_UINT64) > 0) {
            throw new EvidenceContractException(code);
        }
        return result;
    }

    static BigInteger decimal(
            Object value, boolean allowZero, int maximumLength, String code) {
        String text = string(value, code);
        Pattern pattern = allowZero ? NONNEGATIVE_DECIMAL : POSITIVE_DECIMAL;
        if (text.length() > maximumLength || !pattern.matcher(text).matches()) {
            throw new EvidenceContractException(code);
        }
        return new BigInteger(text);
    }
}
