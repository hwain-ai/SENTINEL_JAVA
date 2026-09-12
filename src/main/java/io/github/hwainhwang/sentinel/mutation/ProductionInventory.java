package io.github.hwainhwang.sentinel.mutation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Loads a closed, human-approved inventory and rejects unlisted production Java files. */
public final class ProductionInventory {
    private static final String HEADER = "sentinel-java-production-v1";

    private ProductionInventory() {
        throw new AssertionError("no instances");
    }

    public static List<ProductionSource> load(Path projectRoot, Path inventoryFile)
            throws IOException {
        Path root = canonicalDirectory(projectRoot);
        List<String> paths = inventoryPaths(inventoryFile);
        validateDeclaredPaths(paths);
        Set<String> discovered = discoverProduction(root);
        if (!discovered.equals(new HashSet<>(paths))) {
            throw new IllegalArgumentException("productionInventoryIncomplete");
        }
        List<ProductionSource> sources = new ArrayList<>();
        for (String relative : paths) {
            Path source = regularSource(root, relative);
            sources.add(new ProductionSource(relative, sha256(source)));
        }
        return List.copyOf(sources);
    }

    private static Path canonicalDirectory(Path value) throws IOException {
        if (value == null || !value.isAbsolute() || Files.isSymbolicLink(value)) {
            throw new IllegalArgumentException("projectRootInvalid");
        }
        Path real = value.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.equals(value.normalize()) || !Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("projectRootInvalid");
        }
        return real;
    }

    private static List<String> inventoryPaths(Path inventoryFile) throws IOException {
        requireInventoryFile(inventoryFile);
        String text = decode(Files.readAllBytes(inventoryFile));
        requireInventoryLineEndings(text);
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        requireInventoryHeader(lines);
        return List.of(lines).subList(1, lines.length);
    }

    private static void requireInventoryFile(Path inventoryFile) {
        if (inventoryFile == null) {
            throw new IllegalArgumentException("productionInventoryInvalid");
        }
        requireInventoryNotLinked(inventoryFile);
        requireInventoryRegular(inventoryFile);
    }

    private static void requireInventoryNotLinked(Path inventoryFile) {
        if (Files.isSymbolicLink(inventoryFile)) {
            throw new IllegalArgumentException("productionInventoryInvalid");
        }
    }

    private static void requireInventoryRegular(Path inventoryFile) {
        if (!Files.isRegularFile(inventoryFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("productionInventoryInvalid");
        }
    }

    private static void requireInventoryLineEndings(String text) {
        if (!text.endsWith("\n")) {
            throw new IllegalArgumentException("productionInventoryInvalid");
        }
        if (text.contains("\r")) {
            throw new IllegalArgumentException("productionInventoryInvalid");
        }
    }

    private static void requireInventoryHeader(String[] lines) {
        if (lines.length < 2) {
            throw new IllegalArgumentException("productionInventoryInvalid");
        }
        if (!HEADER.equals(lines[0])) {
            throw new IllegalArgumentException("productionInventoryInvalid");
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
            throw new IllegalArgumentException("productionInventoryInvalid", error);
        }
    }

    private static void validateDeclaredPaths(List<String> paths) {
        String previous = null;
        Set<String> unique = new HashSet<>();
        for (String value : paths) {
            validateRelative(value);
            if (!unique.add(value) || previous != null && compareUtf8(previous, value) >= 0) {
                throw new IllegalArgumentException("productionInventoryOrderInvalid");
            }
            previous = value;
        }
    }

    private static void validateRelative(String value) {
        if (invalidPathText(value)) {
            throw new IllegalArgumentException("productionInventoryPathInvalid");
        }
        Path path = Path.of(value);
        if (invalidPathStructure(path, value)) {
            throw new IllegalArgumentException("productionInventoryPathInvalid");
        }
    }

    private static boolean invalidPathText(String value) {
        return value == null || value.isBlank() || value.indexOf('\\') >= 0
                || !value.endsWith(".java");
    }

    private static boolean invalidPathStructure(Path path, String value) {
        return path.isAbsolute() || !path.normalize().equals(path) || value.startsWith("./")
                || path.getNameCount() == 0 || path.getName(0).toString().equals("..");
    }

    static Set<String> discoverProduction(Path root) throws IOException {
        Set<String> discovered = new HashSet<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return excluded(root.relativize(directory))
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                String relative = portable(root.relativize(file));
                if (isProduction(relative)) {
                    discovered.add(relative);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return discovered;
    }

    private static boolean excluded(Path relative) {
        if (relative.toString().isEmpty()) {
            return false;
        }
        String first = relative.getFileName().toString();
        return Set.of(".git", ".sentinel", ".toolchain", "target", "build", "out")
                .contains(first);
    }

    private static boolean isProduction(String relative) {
        return relative.endsWith(".java")
                && (relative.startsWith("src/main/java/")
                || relative.contains("/src/main/java/"));
    }

    private static Path regularSource(Path root, String relative) throws IOException {
        Path source = root.resolve(relative);
        if (Files.isSymbolicLink(source)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || !source.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(root)) {
            throw new IllegalArgumentException("productionInventoryPathInvalid");
        }
        Object links = Files.getAttribute(source, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        if (((Number) links).longValue() != 1L) {
            throw new IllegalArgumentException("productionSourceHardLinkInvalid");
        }
        return source;
    }

    static String sha256(Path path) throws IOException {
        MessageDigest digest = digest();
        try (var input = Files.newInputStream(path)) {
            byte[] block = new byte[8192];
            for (int count = input.read(block); count >= 0; count = input.read(block)) {
                if (count > 0) {
                    digest.update(block, 0, count);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static int compareUtf8(String left, String right) {
        return java.util.Arrays.compareUnsigned(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static String portable(Path value) {
        return value.toString().replace('\\', '/');
    }
}
