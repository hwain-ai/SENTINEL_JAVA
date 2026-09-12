package io.github.hwainhwang.sentinel.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PitReplayBindingsTest {
    @Test
    void bindsBothSourceAndClassBytesWithOrderIndependentPaths() {
        Map<String, byte[]> sources = Map.of("src/main/java/Value.java", new byte[] {1});
        Map<String, byte[]> classes = Map.of("target/classes/Value.class", new byte[] {2});
        String original = PitReplayBindings.input(sources, classes);
        assertNotEquals(original, PitReplayBindings.input(sources, Map.of("target/classes/Value.class", new byte[] {3})));
        assertNotEquals(original, PitReplayBindings.input(Map.of("src/main/java/Value.java", new byte[] {3}), classes));
        Map<String, byte[]> forward = new LinkedHashMap<>(sources);
        forward.putAll(classes);
        Map<String, byte[]> reverse = new LinkedHashMap<>(classes);
        reverse.putAll(sources);
        assertEquals(PitReplayBindings.contents("domain", forward), PitReplayBindings.contents("domain", reverse));
        assertNotEquals(PitReplayBindings.contents("domain", forward), PitReplayBindings.contents("other", forward));
    }

    @Test
    void refusesProjectClassesThatShadowTheRunnerOrTestFramework() {
        for (String prefix : List.of("target/classes/", "target/test-classes/")) {
            for (String name : List.of("io/github/hwainhwang/sentinel/Fake.class", "org/junit/Fake.class",
                    "org/opentest4j/Fake.class")) {
                assertThrows(IllegalArgumentException.class,
                        () -> PitReplayBindings.requireProjectClasses(Map.of(prefix + name, new byte[] {1})));
            }
        }
        PitReplayBindings.requireProjectClasses(Map.of("target/classes/example/Value.class", new byte[] {1}));
    }

    @Test
    void fingerprintsTheActualFirstPartyClasspathStably() throws Exception {
        assertTrue(Files.exists(PitReplayBindings.runtime()));
        String first = PitReplayBindings.runtimeFingerprint();
        assertTrue(first.matches("[0-9a-f]{64}"));
        assertEquals(first, PitReplayBindings.runtimeFingerprint());
    }
}
