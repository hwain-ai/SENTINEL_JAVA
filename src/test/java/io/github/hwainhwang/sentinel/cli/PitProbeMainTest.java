package io.github.hwainhwang.sentinel.cli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PitProbeMainTest {
    @TempDir Path root;

    @Test
    void exposesSeparateOptInCommand() {
        assertDoesNotThrow(() -> Class.forName("io.github.hwainhwang.sentinel.cli.PitProbeMain"));
    }

    @Test
    void emitsOnlyNonCertifiedObservationAndExitSixForRealPit() throws Exception {
        Path main = root.resolve("src/main/java/example/Value.java");
        Path test = root.resolve("src/test/java/example/ValueTest.java");
        Files.createDirectories(main.getParent());
        Files.createDirectories(test.getParent());
        Files.writeString(main, "package example; public class Value { public boolean positive(int x) { return x > 0; } }");
        Files.writeString(test, "package example; import org.junit.jupiter.api.Test; "
                + "class ValueTest { @Test void checks() { org.junit.jupiter.api.Assertions.assertTrue(new Value().positive(1)); } }");
        Result result = invoke(arguments(root, Path.of(".toolchain/pit-probe").toAbsolutePath()));
        assertEquals(6, result.exit());
        assertTrue(result.out().contains("\"certified\":false"));
        assertTrue(result.out().contains("\"discoveryVerified\":true"));
        assertFalse(result.out().contains(root.toString()));
        assertEquals("pit probe: backendNotAdmitted\n", result.error());
    }

    @Test
    void rejectsInvalidArgumentsWithoutEchoingPrivateValues() {
        for (String[] arguments : new String[][] { {}, {"--private-path", root.toString()},
                {"--project"}, {"--timeout-ms", "secret"}, {"--project", "a", "--project", "b"} }) {
            Result result = invoke(arguments);
            assertEquals(3, result.exit());
            assertEquals("", result.out());
            assertEquals("pit probe: pitProbeUsage\n", result.error());
        }
    }

    @Test
    void missingArtifactErrorsAreStableAndRedacted() {
        Result result = invoke(arguments(root, root.resolve("private-secret-location")));
        assertEquals(5, result.exit());
        assertEquals("", result.out());
        assertEquals("pit probe: pitProbeArtifactsInvalid\n", result.error());
    }

    @Test
    void helpAndCancellationHaveSeparateExitStatuses() {
        assertEquals(0, invoke(new String[] {"--help"}).exit());
        Thread.currentThread().interrupt();
        try {
            Result result = invoke(arguments(root, root.resolve("missing")));
            assertEquals(8, result.exit());
            assertEquals("pit probe: pitProbeCancelled\n", result.error());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static String[] arguments(Path project, Path artifacts) {
        return new String[] {"--project", project.toString(), "--artifacts", artifacts.toString(),
                "--target-class", "example.Value", "--test-class", "example.ValueTest", "--timeout-ms", "120000"};
    }

    private static Result invoke(String[] arguments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int exit = PitProbeMain.run(arguments, new PrintStream(out), new PrintStream(error));
        return new Result(exit, out.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exit, String out, String error) { }
}
