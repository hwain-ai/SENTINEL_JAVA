package io.github.hwainhwang.sentinel.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackendLockScriptTest {
    @TempDir
    Path tempDirectory;

    @Test
    void printsThePinnedJacocoAgentProvenance() throws Exception {
        Result result = run(
                Path.of("backend.lock.json"),
                "jacoco-agent");

        assertEquals(0, result.exitCode());
        assertEquals(
                "0.8.12\n"
                        + "https://repo1.maven.org/maven2/org/jacoco/org.jacoco.agent/0.8.12/"
                        + "org.jacoco.agent-0.8.12-runtime.jar\n"
                        + "302428\n"
                        + "115e8e6e6593ca3a9892dfef695df4d487c706e59e71e64dc0ab95716ee02622\n"
                        + "org.jacoco.agent-0.8.12-runtime.jar\n",
                result.output());
    }

    @Test
    void rejectsAnArtifactWhoseBytesDoNotMatchTheLock() throws Exception {
        Path wrong = tempDirectory.resolve("agent.jar");
        Files.writeString(wrong, "not the pinned JaCoCo agent", StandardCharsets.UTF_8);

        Result result = run(
                Path.of("backend.lock.json"),
                "jacoco-agent",
                "--verify",
                wrong.toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.output().contains("backend lock error: artifact size mismatch"));
    }

    @Test
    void rejectsDuplicateJsonKeys() throws Exception {
        Path duplicate = tempDirectory.resolve("duplicate.json");
        Files.writeString(
                duplicate,
                "{\"repository\":\"SENTINEL_JAVA\",\"repository\":\"SENTINEL_JAVA\"}",
                StandardCharsets.UTF_8);

        Result result = run(duplicate, "jacoco-agent");

        assertEquals(2, result.exitCode());
        assertTrue(result.output().contains("backend lock error: duplicate key"));
    }

    @Test
    void verifiesTheInstalledMutate4javaSourceAndReproducibleJar() throws Exception {
        Result source = run(
                Path.of("backend.lock.json"),
                "mutate4java",
                "--verify-source",
                ".toolchain/backends/mutate4java-7b05fdd-source.tar.gz");
        Result jar = run(
                Path.of("backend.lock.json"),
                "mutate4java",
                "--verify-jar",
                ".toolchain/backends/mutate4java-7b05fdd.jar");

        assertEquals(0, source.exitCode(), source.output());
        assertEquals(0, jar.exitCode(), jar.output());
    }

    @Test
    void rejectsAmbiguousMutationVerificationTargets() throws Exception {
        Result result = run(
                Path.of("backend.lock.json"),
                "mutate4java",
                "--verify-source",
                ".toolchain/backends/mutate4java-7b05fdd-source.tar.gz",
                "--verify-jar",
                ".toolchain/backends/mutate4java-7b05fdd.jar");

        assertEquals(2, result.exitCode(), result.output());
    }

    private static Result run(Path lock, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 4];
        command[0] = "python3";
        command[1] = "-I";
        command[2] = "scripts/backend_lock.py";
        command[3] = lock.toString();
        System.arraycopy(arguments, 0, command, 4, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.waitFor(), output);
    }

    private record Result(int exitCode, String output) {}
}
