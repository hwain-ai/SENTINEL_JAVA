package io.github.hwainhwang.sentinel.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BackendBootstrapTest {
    @Test
    void installsAndVerifiesCoverageArtifactsAndReproducibleMutationBackend() throws Exception {
        Result result = run("./scripts/bootstrap-backends.sh");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(Files.isRegularFile(Path.of(
                ".toolchain/backends/org.jacoco.agent-0.8.12-runtime.jar")));
        assertTrue(Files.isRegularFile(Path.of(
                ".toolchain/backends/org.jacoco.cli-0.8.12-nodeps.jar")));
        assertTrue(Files.isRegularFile(Path.of(
                ".toolchain/backends/mutate4java-7b05fdd-source.tar.gz")));
        assertTrue(Files.isRegularFile(Path.of(
                ".toolchain/backends/mutate4java-7b05fdd.jar")));
    }

    @Test
    void rejectsExplicitBashInvocation() throws Exception {
        Result result = run("/usr/bin/bash", "scripts/bootstrap-backends.sh");

        assertEquals(2, result.exitCode());
        assertTrue(result.output().contains("script must be executed directly"));
    }

    private static Result run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.waitFor(), output);
    }

    private record Result(int exitCode, String output) {}
}
