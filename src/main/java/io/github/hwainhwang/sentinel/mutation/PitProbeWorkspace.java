package io.github.hwainhwang.sentinel.mutation;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Copies only the bounded supported source set, never user build scripts or cached classes. */
final class PitProbeWorkspace implements AutoCloseable {
    private final Path root;
    private final Map<String, byte[]> sources;
    private Map<String, byte[]> classes;

    private PitProbeWorkspace(Path root, Map<String, byte[]> sources) {
        this.root = root;
        this.sources = sources;
        this.classes = Map.of();
    }

    static PitProbeWorkspace create(Map<String, byte[]> sources, long deadline)
            throws IOException, InterruptedException {
        Path root = Files.createTempDirectory("sentinel-java-pit-");
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        PitProbeWorkspace workspace = new PitProbeWorkspace(root, sources);
        try {
            workspace.copy(deadline);
            return workspace;
        } catch (IOException | RuntimeException | InterruptedException error) {
            workspace.close();
            throw error;
        }
    }

    private void copy(long deadline) throws IOException, InterruptedException {
        for (Map.Entry<String, byte[]> source : sources.entrySet()) {
            PitProbeProcess.check(deadline);
            Path destination = root.resolve(source.getKey());
            Files.createDirectories(destination.getParent());
            Files.write(destination, source.getValue());
        }
    }

    Path root() {
        return root;
    }

    List<String> sources(String prefix) {
        List<String> paths = new ArrayList<>();
        for (String path : sources.keySet()) {
            if (path.startsWith(prefix)) {
                paths.add(root.resolve(path).toString());
            }
        }
        paths.sort(String::compareTo);
        return paths;
    }

    void verify(long deadline) throws IOException, InterruptedException {
        PitProbeFiles.verify(root, sources, deadline);
        PitProbeFiles.compare(classes, PitProbeFiles.captureClasses(root, deadline), "pitProbeClassesChanged");
    }

    void sealClasses(long deadline) throws IOException, InterruptedException {
        classes = PitProbeFiles.captureClasses(root, deadline);
    }

    Map<String, byte[]> capturedClasses() {
        Map<String, byte[]> copy = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().clone());
        }
        return copy;
    }

    void applyMutant(String className, byte[] bytes) throws IOException {
        String path = "target/classes/" + className.replace('.', '/') + ".class";
        if (!classes.containsKey(path)) {
            throw new IllegalArgumentException("pitReplayClassMissing");
        }
        PitProbeFiles.regular(root.resolve(path));
        Files.write(root.resolve(path), bytes);
        classes = new LinkedHashMap<>(classes);
        classes.put(path, bytes.clone());
    }

    void requireClasses(List<String> names, String prefix) {
        for (String name : names) {
            if (!classes.containsKey(prefix + name.replace('.', '/') + ".class")) {
                throw new IllegalArgumentException("pitProbeClassMissing");
            }
        }
    }

    @Override
    public void close() throws IOException {
        // RISK(side-effect): delete only this instance's private temporary copy, without following links.
        Files.walkFileTree(root, new Cleanup());
    }

    private static final class Cleanup extends SimpleFileVisitor<Path> {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
            if (error != null) {
                throw error;
            }
            Files.delete(directory);
            return FileVisitResult.CONTINUE;
        }
    }
}
