package io.github.hwainhwang.sentinel.crap;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Position-independent helpers for callable identity and UTF-8 ordering. */
public final class SemanticSite {
    private SemanticSite() {
        throw new AssertionError("no instances");
    }

    public static String lambdaDescriptor(String ownerId, String targetType, String semanticRole) {
        requireText(ownerId, "ownerId");
        requireText(targetType, "targetType");
        requireText(semanticRole, "semanticRole");
        MessageDigest digest = sha256();
        add(digest, ownerId);
        add(digest, targetType);
        add(digest, semanticRole);
        return "lambda{" + targetType + "}:" + java.util.HexFormat.of().formatHex(digest.digest());
    }

    public static String callableId(
            String path,
            String kind,
            String owner,
            String callableName,
            String descriptor,
            String semanticSite) {
        MessageDigest digest = sha256();
        add(digest, "sentinel-java-callable-v2");
        add(digest, path);
        add(digest, kind);
        add(digest, owner);
        add(digest, callableName);
        add(digest, descriptor);
        add(digest, semanticSite == null ? "named" : "anonymous:" + semanticSite);
        return "java:v2:" + java.util.HexFormat.of().formatHex(digest.digest());
    }

    public static byte[] utf8(String value) {
        if (value == null) {
            throw new IllegalArgumentException("textMissing");
        }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("textNotUnicodeScalarValues", error);
        }
    }

    public static int compareUtf8(String left, String right) {
        return java.util.Arrays.compareUnsigned(utf8(left), utf8(right));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = utf8(value);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + "Missing");
        }
        utf8(value);
    }
}
