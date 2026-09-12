package io.github.hwainhwang.sentinel.mutation.junit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

record JUnitRequest(
        String nonce,
        String sourceSha256,
        Path eventFile,
        String hmacKey,
        boolean cacheObserved) {
    private static final Path REQUEST = Path.of("target/sentinel-junit-request-v1");
    private static final Pattern NONCE = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    static JUnitRequest readIfPresent() throws IOException {
        if (!Files.exists(REQUEST, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (Files.isSymbolicLink(REQUEST) || !Files.isRegularFile(REQUEST, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("junitRequestInvalid");
        }
        List<String> lines = Files.readAllLines(REQUEST);
        if (lines.size() != 6 || !lines.get(0).equals("sentinel-java-junit-request-v2")) {
            throw new IllegalArgumentException("junitRequestInvalid");
        }
        return validated(
                lines.get(1), lines.get(2), lines.get(3), lines.get(4), lines.get(5));
    }

    private static JUnitRequest validated(
            String nonce,
            String sourceSha256,
            String eventValue,
            String hmacKey,
            String cacheValue) {
        validateIdentity(nonce, sourceSha256, hmacKey);
        return new JUnitRequest(
                nonce,
                sourceSha256,
                eventPath(eventValue),
                hmacKey,
                cacheObserved(cacheValue));
    }

    private static void validateIdentity(String nonce, String sourceSha256, String hmacKey) {
        if (!NONCE.matcher(nonce).matches()
                || !SHA256.matcher(sourceSha256).matches()
                || !SHA256.matcher(hmacKey).matches()) {
            throw new IllegalArgumentException("junitRequestIdentityInvalid");
        }
    }

    private static boolean cacheObserved(String value) {
        if (value.equals("cache-observed=false")) {
            return false;
        }
        if (value.equals("cache-observed=true")) {
            return true;
        }
        throw new IllegalArgumentException("junitRequestCacheObservationInvalid");
    }

    private static Path eventPath(String eventValue) {
        Path event = Path.of(eventValue);
        if (!event.isAbsolute() || !event.normalize().equals(event)) {
            throw new IllegalArgumentException("junitEventPathInvalid");
        }
        validateEventDestination(event);
        return event;
    }

    private static void validateEventDestination(Path event) {
        Path parent = event.getParent();
        if (parent == null || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(event, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("junitEventPathInvalid");
        }
    }
}
