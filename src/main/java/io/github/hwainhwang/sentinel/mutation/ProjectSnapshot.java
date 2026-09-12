package io.github.hwainhwang.sentinel.mutation;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Set;

/** Disposable regular-file-only copy that keeps all backend writes away from user sources. */
final class ProjectSnapshot implements AutoCloseable {
    private static final Set<String> EXCLUDED = Set.of(
            ".git", ".sentinel", ".toolchain", "target", "build", "out");
    private final Path container;
    private final Path root;

    private ProjectSnapshot(Path container, Path root) {
        this.container = container;
        this.root = root;
    }

    static ProjectSnapshot create(Path sourceRoot) throws IOException {
        Path container = Files.createTempDirectory("sentinel-java-mutation-");
        Files.setPosixFilePermissions(
                container, PosixFilePermissions.fromString("rwx------"));
        Path destination = container.resolve("project");
        Files.createDirectory(destination);
        try {
            copyTree(sourceRoot, destination);
            return new ProjectSnapshot(container, destination);
        } catch (IOException | RuntimeException failure) {
            deleteTree(container);
            throw failure;
        }
    }

    Path root() {
        return root;
    }

    private static void copyTree(Path sourceRoot, Path destination) throws IOException {
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                Path relative = sourceRoot.relativize(directory);
                rejectMavenOverride(relative);
                if (excluded(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                rejectLink(directory);
                if (!relative.toString().isEmpty()) {
                    Files.createDirectory(destination.resolve(relative));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                rejectFile(file, attributes);
                Files.copy(
                        file,
                        destination.resolve(sourceRoot.relativize(file)),
                        StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean excluded(Path relative) {
        return !relative.toString().isEmpty()
                && EXCLUDED.contains(relative.getFileName().toString());
    }

    private static void rejectMavenOverride(Path relative) {
        if (!relative.toString().isEmpty()
                && relative.getFileName().toString().equals(".mvn")) {
            throw new IllegalArgumentException("snapshotMavenOverrideInvalid");
        }
    }

    private static void rejectLink(Path path) {
        if (Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("snapshotSymbolicLinkInvalid");
        }
    }

    private static void rejectFile(Path file, BasicFileAttributes attributes)
            throws IOException {
        rejectLink(file);
        if (!attributes.isRegularFile()) {
            throw new IllegalArgumentException("snapshotFileTypeInvalid");
        }
        Number links = (Number) Files.getAttribute(
                file, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        if (links.longValue() != 1L) {
            throw new IllegalArgumentException("snapshotHardLinkInvalid");
        }
    }

    @Override
    public void close() throws IOException {
        deleteTree(container);
    }

    private static void deleteTree(Path value) throws IOException {
        if (value == null || value.getFileName() == null
                || !value.getFileName().toString().startsWith("sentinel-java-mutation-")) {
            throw new IllegalArgumentException("snapshotCleanupRootInvalid");
        }
        if (!Files.exists(value, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(value)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
