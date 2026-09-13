package io.github.hwainhwang.sentinel.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JUnitRequestScriptTest {
    @TempDir
    Path tempDirectory;

    @Test
    void createsAnOwnerOnlyFreshRequestForTheTypedListener() throws Exception {
        Path root = tempDirectory.resolve("project");
        Path source = root.resolve("src/main/java/Sample.java");
        Path evidence = tempDirectory.resolve("evidence");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Sample {}", StandardCharsets.UTF_8);
        Path stale = root.resolve("target/stale-cache.bin");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "stale", StandardCharsets.UTF_8);
        Files.createDirectory(evidence);
        Files.setPosixFilePermissions(evidence, PosixFilePermissions.fromString("rwx------"));

        Result result = run("write", root.toString(), "src/main/java/Sample.java", evidence.toString());

        assertEquals(0, result.exitCode(), result.output());
        String[] values = result.output().strip().split("\\R");
        assertEquals(4, values.length);
        assertTrue(values[0].matches("[0-9a-f]{32}"));
        assertTrue(values[1].matches("[0-9a-f]{64}"));
        assertTrue(Path.of(values[2]).startsWith(evidence));
        assertTrue(values[3].matches("[0-9a-f]{64}"));
        assertTrue(Files.isRegularFile(root.resolve("target/sentinel-junit-request-v1")));
        assertFalse(Files.exists(evidence.resolve(values[0] + ".key")));
        assertFalse(Files.exists(stale));
        String request = Files.readString(root.resolve("target/sentinel-junit-request-v1"));
        assertTrue(request.startsWith("sentinel-java-junit-request-v2\n"));
        assertTrue(request.endsWith("\ncache-observed=false\n"));
    }

    @Test
    void refusesAnAmbiguousGeneratedOutputCache() throws Exception {
        Path root = tempDirectory.resolve("ambiguous-project");
        Path source = root.resolve("src/main/java/Sample.java");
        Path evidence = tempDirectory.resolve("ambiguous-evidence");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Sample {}", StandardCharsets.UTF_8);
        Files.createDirectory(evidence);
        Files.setPosixFilePermissions(evidence, PosixFilePermissions.fromString("rwx------"));
        Files.createSymbolicLink(root.resolve("target"), tempDirectory.resolve("outside"));

        Result result = run(
                "write", root.toString(), "src/main/java/Sample.java", evidence.toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.output().contains("generated output cache"));
    }

    private static Result run(String... arguments) throws Exception {
        String[] command = new String[arguments.length + 3];
        command[0] = "python3";
        command[1] = "-I";
        command[2] = "scripts/junit_request.py";
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.waitFor(), output);
    }

    private record Result(int exitCode, String output) {}
}
