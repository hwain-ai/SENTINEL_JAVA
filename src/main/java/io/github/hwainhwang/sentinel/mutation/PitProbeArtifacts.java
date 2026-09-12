package io.github.hwainhwang.sentinel.mutation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One packaged artifact manifest serves both the installer and the runtime verifier. */
final class PitProbeArtifacts {
    private static final Set<String> ROLES = Set.of("core", "entry", "cli", "junit5", "text", "lang", "junit-console");
    private final Path root;
    private final Map<String, Artifact> artifacts;

    private PitProbeArtifacts(Path root, Map<String, Artifact> artifacts) {
        this.root = root;
        this.artifacts = artifacts;
    }

    static PitProbeArtifacts verify(Path root) throws IOException {
        try {
            Path canonical = PitProbeFiles.directory(root);
            requireClasspathPath(canonical);
            Map<String, Artifact> artifacts = manifest();
            for (Artifact artifact : artifacts.values()) {
                verifyOne(canonical.resolve(artifact.file()), artifact);
            }
            return new PitProbeArtifacts(canonical, artifacts);
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException("pitProbeArtifactsInvalid");
        }
    }

    private static void requireClasspathPath(Path root) {
        if (root.toString().contains(",") || root.toString().contains(java.io.File.pathSeparator)) {
            throw new IllegalArgumentException("pitProbeArtifactsInvalid");
        }
    }

    private static Map<String, Artifact> manifest() throws IOException {
        try (var input = PitProbeArtifacts.class.getResourceAsStream("/pit-probe-artifacts.tsv")) {
            if (input == null) {
                throw new IllegalArgumentException("pitProbeManifestInvalid");
            }
            String[] lines = new String(input.readNBytes(16385), StandardCharsets.UTF_8).split("\n");
            if (lines.length != 8 || !lines[0].equals("sentinel-java-pit-artifacts-v1")) {
                throw new IllegalArgumentException("pitProbeManifestInvalid");
            }
            return readManifest(lines);
        }
    }

    private static Map<String, Artifact> readManifest(String[] lines) {
        Map<String, Artifact> artifacts = new LinkedHashMap<>();
        for (int index = 1; index < lines.length; index++) {
            String[] fields = lines[index].split("\t", -1);
            requireManifestRow(fields);
            Artifact artifact = new Artifact(fields[1], Long.parseLong(fields[2]), fields[3], fields[4]);
            if (artifacts.put(fields[0], artifact) != null) {
                throw new IllegalArgumentException("pitProbeManifestInvalid");
            }
        }
        if (!artifacts.keySet().equals(ROLES)) {
            throw new IllegalArgumentException("pitProbeManifestInvalid");
        }
        return artifacts;
    }

    private static void requireManifestRow(String[] fields) {
        if (fields.length != 8 || !fields[2].matches("[1-9][0-9]*")
                || !fields[3].matches("[0-9a-f]{64}") || !fields[4].matches("[a-z0-9.-]+\\.jar")) {
            throw new IllegalArgumentException("pitProbeManifestInvalid");
        }
    }

    private static void verifyOne(Path file, Artifact artifact) throws IOException {
        byte[] bytes = PitProbeFiles.read(file, Math.toIntExact(artifact.size()), "pitProbeArtifactsInvalid");
        if (bytes.length != artifact.size() || !PitProbeFiles.sha256(bytes).equals(artifact.sha256())) {
            throw new IllegalArgumentException("pitProbeArtifactsInvalid");
        }
    }

    String classpath() {
        List<String> paths = new ArrayList<>();
        for (Artifact artifact : artifacts.values()) {
            paths.add(root.resolve(artifact.file()).toString());
        }
        return String.join(java.io.File.pathSeparator, paths);
    }

    Path console() {
        return root.resolve(artifacts.get("junit-console").file());
    }

    Path core() {
        return root.resolve(artifacts.get("core").file());
    }

    String version() {
        return artifacts.get("core").version();
    }

    String fingerprint() {
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, Artifact> entry : artifacts.entrySet()) {
            values.add(entry.getKey());
            values.add(entry.getValue().version());
            values.add(entry.getValue().sha256());
        }
        return MutationHash.digest("sentinel-java-pit-artifacts-v1", values);
    }

    private record Artifact(String version, long size, String sha256, String file) { }
}
