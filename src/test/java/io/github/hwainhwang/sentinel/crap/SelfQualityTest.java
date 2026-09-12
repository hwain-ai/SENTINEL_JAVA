package io.github.hwainhwang.sentinel.crap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SelfQualityTest {
    @Test
    void everyProductionCallableHasComplexityAtMostEight() throws IOException {
        Path root = Path.of("").toAbsolutePath().normalize();
        Path sourceRoot = root.resolve("src/main/java");
        Map<String, byte[]> sources = new LinkedHashMap<>();
        try (var paths = Files.walk(sourceRoot)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .forEach(path -> sources.put(
                            root.relativize(path).toString().replace('\\', '/'),
                            read(path)));
        }

        List<Models.CallableDefinition> definitions = JavaAnalyzer.analyzeAll(
                sources,
                List.of(
                        dependency(root, "junit-platform-launcher", "junit-platform-launcher"),
                        dependency(root, "junit-platform-engine", "junit-platform-engine"),
                        dependency(root, "junit-platform-commons", "junit-platform-commons"),
                        root.resolve(".toolchain/m2/org/opentest4j/opentest4j/1.3.0/"
                                + "opentest4j-1.3.0.jar")));

        assertFalse(definitions.isEmpty());
        assertTrue(
                definitions.stream().allMatch(item -> item.complexity() <= 8),
                () -> definitions.stream()
                        .filter(item -> item.complexity() > 8)
                        .map(item -> item.identity().owner() + "#"
                                + item.identity().callableName() + item.identity().descriptor()
                                + "=" + item.complexity())
                        .toList()
                        .toString());
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static Path dependency(Path root, String directory, String artifact) {
        return root.resolve(".toolchain/m2/org/junit/platform/" + directory + "/1.10.2/"
                + artifact + "-1.10.2.jar");
    }
}
