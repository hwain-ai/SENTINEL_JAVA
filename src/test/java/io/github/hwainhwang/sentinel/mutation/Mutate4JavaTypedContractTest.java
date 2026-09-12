package io.github.hwainhwang.sentinel.mutation;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Mutate4JavaTypedContractTest {
    @Test
    void doesNotInterpretBackendStdoutAsMutationResults() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/hwainhwang/sentinel/mutation/Mutate4JavaAdapter.java"));

        assertFalse(source.contains("resultIdentities("));
    }

    @Test
    void listenerDoesNotHardcodeCacheObservation() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/hwainhwang/sentinel/mutation/junit/"
                        + "SentinelTestExecutionListener.java"));

        assertFalse(source.contains("\\\"cacheObserved\\\":false"));
    }
}
