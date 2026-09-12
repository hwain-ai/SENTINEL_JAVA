package io.github.hwainhwang.sentinel.mutation;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, regular-file-only source input for the explicitly supported standalone profile. */
final class PitProbeFiles {
    static final int MAX_SOURCE_BYTES = 4 * 1024 * 1024;
    static final int MAX_REPORT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 16 * 1024 * 1024;

    private PitProbeFiles() {
        throw new AssertionError("no instances");
    }

    static Path directory(Path input) throws IOException {
        if (input == null || !input.isAbsolute() || !input.normalize().equals(input)) {
            throw new IllegalArgumentException("pitProbeSourceInvalid");
        }
        Path real = input.toRealPath();
        if (!real.equals(input) || !Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("pitProbeSourceInvalid");
        }
        return real;
    }

    static void regular(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("pitProbeSourceInvalid");
        }
        if (((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1) {
            throw new IllegalArgumentException("pitProbeSourceInvalid");
        }
        if (!path.toRealPath().equals(path.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("pitProbeSourceInvalid");
        }
    }

    static byte[] read(Path path, int maximum, String failure) throws IOException {
        regular(path);
        if (Files.size(path) > maximum) {
            throw new IllegalArgumentException(failure);
        }
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) {
                throw new IllegalArgumentException(failure);
            }
            return bytes;
        }
    }

    static Map<String, byte[]> capture(Path root, long deadline) throws IOException, InterruptedException {
        directory(root);
        rejectResources(root);
        Collector collector = new Collector(root, deadline, ".java");
        collectDirectory(root.resolve("src/main/java"), collector);
        collectDirectory(root.resolve("src/test/java"), collector);
        return collector.sources;
    }

    static Map<String, byte[]> captureClasses(Path root, long deadline) throws IOException, InterruptedException {
        Collector collector = new Collector(root, deadline, ".class");
        collectDirectory(root.resolve("target/classes"), collector);
        collectDirectory(root.resolve("target/test-classes"), collector);
        return collector.sources;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("sha256Unavailable");
        }
    }

    private static void rejectResources(Path root) {
        if (Files.exists(root.resolve("src/main/resources")) || Files.exists(root.resolve("src/test/resources"))) {
            throw new IllegalArgumentException("pitProbeResourcesUnsupported");
        }
    }

    private static void collectDirectory(Path directory, Collector collector) throws IOException, InterruptedException {
        PitProbeProcess.check(collector.deadline);
        directory(directory);
        Files.walkFileTree(directory, collector);
        PitProbeProcess.check(collector.deadline);
    }

    static void verify(Path root, Map<String, byte[]> expected, long deadline) throws IOException, InterruptedException {
        Map<String, byte[]> current = capture(root, deadline);
        compare(expected, current, "pitProbeSourceChanged");
    }

    static void compare(Map<String, byte[]> expected, Map<String, byte[]> current, String failure) {
        if (!current.keySet().equals(expected.keySet())) {
            throw new IllegalArgumentException(failure);
        }
        for (String path : expected.keySet()) {
            if (!Arrays.equals(expected.get(path), current.get(path))) {
                throw new IllegalArgumentException(failure);
            }
        }
    }

    private static final class Collector extends SimpleFileVisitor<Path> {
        private final Path root;
        private final long deadline;
        private final String extension;
        private final Map<String, byte[]> sources;
        private int total;

        Collector(Path root, long deadline, String extension) {
            this.root = root;
            this.deadline = deadline;
            this.extension = extension;
            this.sources = new LinkedHashMap<>();
        }

        @Override
        public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) {
            checkVisit();
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            checkVisit();
            if (!file.toString().endsWith(extension) || file.getFileName().toString().equals("module-info.java")) {
                throw new IllegalArgumentException("pitProbeSourceInvalid");
            }
            byte[] source = read(file, MAX_SOURCE_BYTES, "pitProbeSourceTooLarge");
            total += source.length;
            if (total > MAX_TOTAL_BYTES || sources.size() >= 256) {
                throw new IllegalArgumentException("pitProbeSourceTooLarge");
            }
            sources.put(root.relativize(file).toString().replace('\\', '/'), source);
            return FileVisitResult.CONTINUE;
        }

        private void checkVisit() {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalArgumentException("pitProbeCancelled");
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalArgumentException("pitProbeTimedOut");
            }
        }
    }
}
