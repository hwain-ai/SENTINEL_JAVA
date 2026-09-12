package io.github.hwainhwang.sentinel.mutation;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Fingerprints all captured input bytes and the actual first-party replay classpath. */
final class PitReplayBindings {
    static final String PROFILE = "pit-jupiter-method-replay-v1";

    private PitReplayBindings() {
        throw new AssertionError("no instances");
    }

    static String contents(String domain, Map<String, byte[]> contents) {
        List<String> values = new ArrayList<>();
        for (var entry : new TreeMap<>(contents).entrySet()) {
            values.add(entry.getKey());
            values.add(PitProbeFiles.sha256(entry.getValue()));
        }
        return MutationHash.digest(domain, values);
    }

    static String input(Map<String, byte[]> sources, Map<String, byte[]> classes) {
        Map<String, byte[]> all = new LinkedHashMap<>(sources);
        all.putAll(classes);
        return contents("sentinel-java-pit-input-v1", all);
    }

    static Path runtime() throws IOException {
        try {
            Path path = Path.of(PitReplayBindings.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!path.toRealPath().equals(path) || path.toString().contains(java.io.File.pathSeparator)) {
                throw new IllegalArgumentException("pitReplayRuntimeInvalid");
            }
            return path;
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("pitReplayRuntimeInvalid", error);
        }
    }

    static String runtimeFingerprint() throws IOException {
        Path root = runtime();
        if (Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)) {
            return PitProbeFiles.sha256(PitProbeFiles.read(root, 32 * 1024 * 1024, "pitReplayRuntimeTooLarge"));
        }
        Map<String, byte[]> contents = new LinkedHashMap<>();
        int total = 0;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                byte[] bytes = PitProbeFiles.read(path, PitProbeFiles.MAX_SOURCE_BYTES, "pitReplayRuntimeTooLarge");
                total += bytes.length;
                if (total > 32 * 1024 * 1024 || contents.size() >= 1024) {
                    throw new IllegalArgumentException("pitReplayRuntimeTooLarge");
                }
                contents.put(root.relativize(path).toString(), bytes);
            }
        }
        return contents("sentinel-java-pit-runner-v1", contents);
    }

    static void requireProjectClasses(Map<String, byte[]> classes) {
        for (String path : classes.keySet()) {
            String relative = path.substring(path.indexOf('/', "target/".length()) + 1);
            if (relative.startsWith("io/github/hwainhwang/sentinel/") || relative.startsWith("org/junit/")
                    || relative.startsWith("org/opentest4j/")) {
                throw new IllegalArgumentException("pitReplayReservedClass");
            }
        }
    }
}
