package io.github.hwainhwang.sentinel.mutation;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;

/** Domain-separated SHA-256 helpers for typed test evidence. */
public final class MutationHash {
    private MutationHash() {
        throw new AssertionError("no instances");
    }

    public static String digest(String domain, Collection<String> values) {
        MessageDigest digest = sha256();
        add(digest, domain);
        for (String value : values) {
            add(digest, value);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String digest(String domain, String value) {
        return digest(domain, java.util.List.of(value));
    }

    private static void add(MessageDigest digest, String value) {
        if (value == null) {
            throw new IllegalArgumentException("hashValueMissing");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
