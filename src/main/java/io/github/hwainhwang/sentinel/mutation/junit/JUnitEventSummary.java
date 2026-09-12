package io.github.hwainhwang.sentinel.mutation.junit;

import io.github.hwainhwang.sentinel.mutation.ExecutionStatus;
import io.github.hwainhwang.sentinel.mutation.TestExecution;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Strict authenticated parser and writer for one canonical JUnit execution event. */
public record JUnitEventSummary(String sourceSha256, TestExecution execution) {
    private static final Pattern CANONICAL = Pattern.compile(
            "\\{\"schemaVersion\":\"sentinel-java-junit-event-v2\","
                    + "\"nonce\":\"([0-9a-f]{32})\","
                    + "\"sourceSha256\":\"([0-9a-f]{64})\","
                    + "\"inventorySha256\":\"([0-9a-f]{64})\","
                    + "\"status\":\"([A-Z_]+)\","
                    + "\"testId\":\"([0-9a-f]*)\","
                    + "\"assertionType\":\"([A-Za-z0-9_.$]*)\","
                    + "\"failureSignature\":\"([0-9a-f]*)\","
                    + "\"cacheObserved\":(true|false),"
                    + "\"retryObserved\":(true|false),"
                    + "\"eventHmac\":\"([0-9a-f]{64})\"\\}\\n");
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{64}");
    private static final String MAC_DOMAIN = "sentinel-java-junit-event-hmac-v1\n";

    public static JUnitEventSummary read(Path path, String keyHex) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("junitEventMissing");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(4097);
        }
        if (bytes.length == 0 || bytes.length > 4096) {
            throw new IllegalArgumentException("junitEventSizeInvalid");
        }
        String text = decode(bytes);
        Matcher match = CANONICAL.matcher(text);
        if (!match.matches()) {
            throw new IllegalArgumentException("junitEventInvalid");
        }
        JUnitEventSummary summary = parsed(match);
        requireAuthentication(summary.unsigned(), match.group(10), keyHex);
        return summary;
    }

    public byte[] authenticatedFile(String keyHex) {
        validateIdentity(sourceSha256, execution);
        validateFailureFields(
                execution.status(),
                execution.testId(),
                execution.assertionType(),
                execution.failureSignature());
        String unsigned = unsigned();
        String prefix = unsigned.substring(0, unsigned.length() - 2);
        String payload = prefix + ",\"eventHmac\":\""
                + hmac(unsigned, keyHex) + "\"}\n";
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    private static JUnitEventSummary parsed(Matcher match) {
        ExecutionStatus status;
        try {
            status = ExecutionStatus.valueOf(match.group(4));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("junitEventStatusInvalid", error);
        }
        validateFailureFields(status, match.group(5), match.group(6), match.group(7));
        TestExecution execution = new TestExecution(
                status,
                match.group(3),
                match.group(5),
                match.group(6),
                match.group(7),
                match.group(1),
                Boolean.parseBoolean(match.group(8)),
                Boolean.parseBoolean(match.group(9)));
        return new JUnitEventSummary(match.group(2), execution);
    }

    private static void validateIdentity(String sourceSha256, TestExecution execution) {
        if (sourceSha256 == null || !KEY.matcher(sourceSha256).matches()
                || execution == null || !KEY.matcher(execution.inventorySha256()).matches()
                || !execution.nonce().matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("junitEventIdentityInvalid");
        }
    }

    private static void validateFailureFields(
            ExecutionStatus status, String testId, String assertionType, String signature) {
        boolean hasFailure = !testId.isEmpty() || !assertionType.isEmpty() || !signature.isEmpty();
        if (status == ExecutionStatus.ASSERTION_FAILURE) {
            if (testId.length() != 64 || assertionType.isEmpty() || signature.length() != 64) {
                throw new IllegalArgumentException("junitAssertionEvidenceInvalid");
            }
        } else if (hasFailure) {
            throw new IllegalArgumentException("junitNonAssertionEvidenceInvalid");
        }
    }

    private String unsigned() {
        return "{\"schemaVersion\":\"sentinel-java-junit-event-v2\","
                + "\"nonce\":\"" + execution.nonce() + "\","
                + "\"sourceSha256\":\"" + sourceSha256 + "\","
                + "\"inventorySha256\":\"" + execution.inventorySha256() + "\","
                + "\"status\":\"" + execution.status().name() + "\","
                + "\"testId\":\"" + execution.testId() + "\","
                + "\"assertionType\":\"" + execution.assertionType() + "\","
                + "\"failureSignature\":\"" + execution.failureSignature() + "\","
                + "\"cacheObserved\":" + execution.cacheObserved() + ","
                + "\"retryObserved\":" + execution.retryObserved() + "}\n";
    }

    private static void requireAuthentication(
            String unsigned, String actualHmac, String keyHex) {
        String expectedHmac = hmac(unsigned, keyHex);
        if (!MessageDigest.isEqual(
                expectedHmac.getBytes(StandardCharsets.US_ASCII),
                actualHmac.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("junitEventHmacInvalid");
        }
    }

    static String hmac(String unsigned, String keyHex) {
        if (keyHex == null || !KEY.matcher(keyHex).matches()) {
            throw new IllegalArgumentException("junitEventHmacKeyInvalid");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HexFormat.of().parseHex(keyHex), "HmacSHA256"));
            mac.update(MAC_DOMAIN.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("junitEventHmacUnavailable", error);
        }
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("junitEventUtf8Invalid", error);
        }
    }
}
