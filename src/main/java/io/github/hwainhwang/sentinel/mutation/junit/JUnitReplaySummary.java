package io.github.hwainhwang.sentinel.mutation.junit;

import io.github.hwainhwang.sentinel.mutation.MutationHash;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

/** Authenticated opt-in result fingerprint; the existing JUnit v2 event remains unchanged. */
public record JUnitReplaySummary(JUnitEventSummary summary, String resultsSha256) {
    private static final String HEADER = "sentinel-java-junit-replay-v1";

    public static JUnitReplaySummary read(Path event, String key) throws IOException {
        JUnitEventSummary summary = JUnitEventSummary.read(event, key);
        String[] lines = readLines(companion(event));
        if (lines.length != 7 || !lines[0].equals(HEADER) || !lines[6].isEmpty()
                || !lines[3].matches("[0-9a-f]{64}") || !lines[5].matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("junitReplayInvalid");
        }
        JUnitReplaySummary replay = new JUnitReplaySummary(summary, lines[3]);
        String unsigned = replay.unsigned(key);
        if (!String.join("\n", List.of(lines[0], lines[1], lines[2], lines[3], lines[4], "")).equals(unsigned)
                || !MessageDigest.isEqual(lines[5].getBytes(StandardCharsets.US_ASCII),
                        JUnitEventSummary.hmac(unsigned, key).getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("junitReplayBindingInvalid");
        }
        return replay;
    }

    static void write(JUnitRequest request, String results) throws IOException {
        JUnitEventSummary summary = JUnitEventSummary.read(request.eventFile(), request.hmacKey());
        JUnitReplaySummary replay = new JUnitReplaySummary(summary, results);
        String unsigned = replay.unsigned(request.hmacKey());
        JUnitPrivateFiles.write(companion(request.eventFile()),
                (unsigned + JUnitEventSummary.hmac(unsigned, request.hmacKey()) + "\n")
                        .getBytes(StandardCharsets.US_ASCII));
    }

    private String unsigned(String key) {
        String event = new String(summary.authenticatedFile(key), StandardCharsets.UTF_8);
        return HEADER + "\n" + summary.sourceSha256() + "\n" + summary.execution().nonce() + "\n"
                + resultsSha256 + "\n" + MutationHash.digest("sentinel-java-replay-event-v1", List.of(event)) + "\n";
    }

    private static Path companion(Path event) {
        return event.resolveSibling(event.getFileName() + ".replay");
    }

    private static String[] readLines(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("junitReplayMissing");
        }
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(1025);
            if (bytes.length > 1024) {
                throw new IllegalArgumentException("junitReplayTooLarge");
            }
            return new String(bytes, StandardCharsets.US_ASCII).split("\n", -1);
        }
    }
}
