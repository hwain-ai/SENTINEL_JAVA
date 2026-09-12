package io.github.hwainhwang.sentinel.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PitReplayEventPathTest {
    @TempDir Path root;

    @Test
    void refusesToWriteEventsThroughATestReplacedParent() throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path project = PitProbeTest.fixture(root.resolve("project"),
                "if (java.nio.file.Files.exists(java.nio.file.Path.of(\"target/sentinel-junit-request-v1\"))) {\n"
                + "java.nio.file.Files.delete(java.nio.file.Path.of(\"target/pit-events\"));\n"
                + "java.nio.file.Files.createSymbolicLink(java.nio.file.Path.of(\"target/pit-events\"), java.nio.file.Path.of(\""
                + outside + "\"));\n}");
        assertThrows(Exception.class, () -> PitProbe.run(PitProbeTest.request(project)));
        try (var files = Files.list(outside)) {
            assertEquals(0, files.count(), "the runner wrote events outside its private workspace");
        }
    }
}
