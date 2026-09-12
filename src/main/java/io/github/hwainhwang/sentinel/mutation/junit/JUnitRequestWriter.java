package io.github.hwainhwang.sentinel.mutation.junit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Exclusively creates the private request consumed by the typed JUnit listener. */
public final class JUnitRequestWriter {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private JUnitRequestWriter() {
        throw new AssertionError("no instances");
    }

    public static Request create(
            Path projectRoot,
            Path eventDirectory,
            String sourceSha256,
            boolean cacheObserved)
            throws IOException {
        validateInputs(projectRoot, eventDirectory, sourceSha256);
        Path target = projectRoot.resolve("target");
        createTarget(target);
        Path request = target.resolve("sentinel-junit-request-v1");
        String nonce = nonce();
        String hmacKey = hmacKey();
        Path event = eventDirectory.resolve(nonce + ".json");
        if (Files.exists(request, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(event, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("junitRequestCollision");
        }
        byte[] content = ("sentinel-java-junit-request-v2\n"
                + nonce + "\n" + sourceSha256 + "\n" + event + "\n"
                + hmacKey + "\ncache-observed=" + cacheObserved + "\n")
                .getBytes(StandardCharsets.UTF_8);
        writePrivateRequest(request, content);
        return new Request(
                request, event, nonce, sourceSha256, hmacKey, cacheObserved);
    }

    private static void writePrivateRequest(Path request, byte[] content) throws IOException {
        Files.createFile(
                request,
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")));
        try {
            Files.write(request, content, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(request);
            throw failure;
        }
    }

    private static void validateInputs(
            Path projectRoot, Path eventDirectory, String sourceSha256) throws IOException {
        if (!canonicalDirectory(projectRoot, false)
                || !canonicalDirectory(eventDirectory, true)
                || sourceSha256 == null || !SHA256.matcher(sourceSha256).matches()) {
            throw new IllegalArgumentException("junitRequestInputInvalid");
        }
    }

    private static boolean canonicalDirectory(Path path, boolean ownerOnly) throws IOException {
        if (path == null || !path.isAbsolute() || Files.isSymbolicLink(path)
                || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || !path.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(path.normalize())) {
            return false;
        }
        int mode = (Integer) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
        return !ownerOnly || (mode & 0077) == 0;
    }

    private static void createTarget(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(
                    target,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
        }
        if (Files.isSymbolicLink(target)
                || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("junitRequestTargetInvalid");
        }
    }

    private static String nonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String hmacKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public record Request(
            Path requestFile,
            Path eventFile,
            String nonce,
            String sourceSha256,
            String hmacKey,
            boolean cacheObserved)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            Files.deleteIfExists(requestFile);
        }
    }
}
