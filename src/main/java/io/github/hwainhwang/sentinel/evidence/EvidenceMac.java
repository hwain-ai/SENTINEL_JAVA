package io.github.hwainhwang.sentinel.evidence;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Domain-separated HMAC-SHA-256 operations for private evidence. */
final class EvidenceMac {
    private EvidenceMac() {
        throw new AssertionError("no instances");
    }

    static String record(byte[] key, String keyDomain, String macDomain, Object body) {
        requireKey(key);
        byte[] derived = hmac(key, keyDomain.getBytes(StandardCharsets.US_ASCII));
        byte[] domain = macDomain.getBytes(StandardCharsets.US_ASCII);
        byte[] bodyBytes = CanonicalJson.encode(body);
        byte[] input = new byte[domain.length + bodyBytes.length];
        System.arraycopy(domain, 0, input, 0, domain.length);
        System.arraycopy(bodyBytes, 0, input, domain.length, bodyBytes.length);
        return HexFormat.of().formatHex(hmac(derived, input));
    }

    static String direct(byte[] key, String domain, byte[] value) {
        requireKey(key);
        byte[] prefix = domain.getBytes(StandardCharsets.US_ASCII);
        byte[] input = new byte[prefix.length + value.length];
        System.arraycopy(prefix, 0, input, 0, prefix.length);
        System.arraycopy(value, 0, input, prefix.length, value.length);
        return HexFormat.of().formatHex(hmac(key, input));
    }

    static boolean matches(String leftHex, String rightHex) {
        return MessageDigest.isEqual(
                leftHex.getBytes(StandardCharsets.US_ASCII),
                rightHex.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] hmac(byte[] key, byte[] input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(input);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("HmacSHA256 unavailable", error);
        }
    }

    private static void requireKey(byte[] key) {
        if (key == null || key.length != 32) {
            throw new EvidenceContractException("cleanupLeaseKeyInvalid");
        }
    }
}
