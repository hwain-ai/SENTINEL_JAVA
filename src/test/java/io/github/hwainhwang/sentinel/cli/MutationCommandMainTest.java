package io.github.hwainhwang.sentinel.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hwainhwang.sentinel.mutation.MutationCandidate;
import io.github.hwainhwang.sentinel.mutation.MutationGate;
import io.github.hwainhwang.sentinel.mutation.MutationRecord;
import io.github.hwainhwang.sentinel.mutation.MutationRun;
import io.github.hwainhwang.sentinel.mutation.MutationState;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MutationCommandMainTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exposesTheKilledOnlyEvidenceComponentFromExplicitInputs() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MutationCandidate candidate = new MutationCandidate(
                "a".repeat(64),
                "src/main/java/demo/Flag.java",
                "b".repeat(64),
                4,
                "replace true with false",
                1);
        List<MutationRecord> records = List.of(
                new MutationRecord(candidate, MutationState.KILLED));

        int exit = MutationCommandMain.run(
                arguments(),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()),
                request -> new MutationRun(records, MutationGate.component(records)));

        assertEquals(0, exit);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"inScope\":1"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"pass\":true"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"file\":\"src/main/java/demo/Flag.java\""));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"line\":4"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"status\":\"killed\""));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"description\":\"replace true with false\""));
    }

    @Test
    void passesSelectedLinesTestsAndUnlimitedTimeoutToTheBackend() {
        String[] values = withOptions(
                "--changed-file", "src/main/java/demo/Flag.java",
                "--lines", "4,7,4",
                "--tests", "demo.FlagTest,demo.Container$NestedTest",
                "--mutation-min", "75");
        values[15] = "0";
        var seen = new java.util.concurrent.atomic.AtomicReference<io.github.hwainhwang.sentinel.mutation.ProjectMutationRequest>();
        int exit = MutationCommandMain.run(values,
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()), request -> {
                    seen.set(request);
                    return new MutationRun(List.of(), MutationGate.component(List.of()));
                });

        assertEquals(2, exit);
        assertEquals(java.util.Set.of(4, 7), seen.get().lines());
        assertEquals(List.of("demo.FlagTest", "demo.Container$NestedTest"), seen.get().tests());
        assertEquals(0, seen.get().timeoutMillis());
        assertEquals("75", seen.get().mutationMin().text());
    }

    @Test
    void rejectsInvalidLineSelectionsBeforeCallingTheBackend() {
        List<String[]> cases = List.of(
                withOptions("--lines", "4"),
                withOptions("--changed-file", "src/main/java/A.java", "--lines", "0"),
                withOptions("--changed-file", "src/main/java/A.java", "--lines", "-1"),
                withOptions("--changed-file", "src/main/java/A.java",
                        "--changed-file", "src/main/java/B.java", "--lines", "4"));
        for (String[] values : cases) {
            assertRejected(values, "functionSelectionInvalid");
        }
    }

    @Test
    void rejectsTestPatternsAndEmptyClassNamesBeforeCallingTheBackend() {
        for (String value : List.of("demo.*", "demo.FlagTest,", "demo.FlagTest#method", "demo..FlagTest")) {
            assertRejected(withOptions("--tests", value), "testSelectionInvalid");
        }
    }

    @Test
    void rejectsUnknownDuplicateAndEmptyOptionsBeforeCallingTheBackend() {
        assertRejected(withOptions("--unknown", "value"), "usage");
        assertRejected(withOptions("--project", "/tmp/duplicate"), "usage");
        assertRejected(withOptions("--tests", ""), "usage");
    }

    @Test
    void passesChangedFilesAsMutationTargetsAndRejectsEscapingPaths() {
        String[] values = arguments();
        String[] withTargets = java.util.Arrays.copyOf(values, values.length + 4);
        withTargets[values.length] = "--changed-file";
        withTargets[values.length + 1] = "src/main/java/demo/Flag.java";
        withTargets[values.length + 2] = "--changed-file";
        withTargets[values.length + 3] = "src/main/java/demo/Other.java";
        java.util.concurrent.atomic.AtomicReference<java.util.Set<String>> seen = new java.util.concurrent.atomic.AtomicReference<>();

        int exit = MutationCommandMain.run(
                withTargets,
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()),
                request -> {
                    seen.set(request.targets());
                    return new MutationRun(List.of(), MutationGate.component(List.of()));
                });

        assertEquals(2, exit);
        assertEquals(java.util.Set.of("src/main/java/demo/Flag.java", "src/main/java/demo/Other.java"), seen.get());

        String[] escaping = java.util.Arrays.copyOf(values, values.length + 2);
        escaping[values.length] = "--changed-file";
        escaping[values.length + 1] = "../outside/Flag.java";
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int rejected = MutationCommandMain.run(
                escaping,
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(error, true, StandardCharsets.UTF_8),
                request -> {
                    throw new AssertionError("backend must not run");
                });
        assertEquals(4, rejected);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("changedPathInvalid"));
    }

    @Test
    void rejectsMissingOrUnknownOptionsBeforeRunningTheBackend() {
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int missing = MutationCommandMain.run(
                new String[]{"--project", "/tmp/project"},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(error, true, StandardCharsets.UTF_8),
                request -> {
                    throw new AssertionError("backend must not run");
                });

        assertEquals(4, missing);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("usage"));
    }

    @Test
    void defaultEntrypointFailsClosedBeforeAProcessWhenProjectRootIsMissing() {
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        String[] values = arguments();
        values[1] = temporaryDirectory.resolve("missing-project").toString();

        int exit = MutationCommandMain.run(
                values,
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(error, true, StandardCharsets.UTF_8));

        assertEquals(4, exit);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("NoSuchFileException"));
    }

    @Test
    void exposesOnlySafeDiagnosticCodesFromInjectedExecutionFailures() {
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = MutationCommandMain.run(
                arguments(),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(error, true, StandardCharsets.UTF_8),
                request -> {
                    throw new IllegalArgumentException("typedFailure");
                });

        assertEquals(4, exit);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("typedFailure"));
    }

    @Test
    void failsClosedWhenPassingEvidenceCannotBeWritten() {
        MutationCandidate candidate = new MutationCandidate(
                "a".repeat(64),
                "src/main/java/demo/Flag.java",
                "b".repeat(64),
                4,
                "replace true with false",
                1);
        List<MutationRecord> records = List.of(
                new MutationRecord(candidate, MutationState.KILLED));
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        PrintStream brokenOutput = new PrintStream(new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("closed output");
            }
        });

        int exit = MutationCommandMain.run(
                arguments(),
                brokenOutput,
                new PrintStream(error, true, StandardCharsets.UTF_8),
                request -> new MutationRun(records, MutationGate.component(records)));

        assertEquals(4, exit);
        assertTrue(error.toString(StandardCharsets.UTF_8)
                .contains("mutationOutputWriteFailed"));
    }

    private static String[] arguments() {
        return new String[]{
            "--project", "/tmp/project",
            "--inventory", "/tmp/inventory",
            "--backend", "/tmp/backend.jar",
            "--java-home", "/tmp/java",
            "--maven-home", "/tmp/maven",
            "--maven-repository", "/tmp/m2",
            "--listener", "/tmp/listener.jar",
            "--timeout-millis", "120000"
        };
    }

    private static String[] withOptions(String... extra) {
        String[] defaults = arguments();
        String[] values = java.util.Arrays.copyOf(defaults, defaults.length + extra.length);
        System.arraycopy(extra, 0, values, defaults.length, extra.length);
        return values;
    }

    private static void assertRejected(String[] values, String diagnostic) {
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int exit = MutationCommandMain.run(values,
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(error, true, StandardCharsets.UTF_8), request -> {
                    throw new AssertionError("backend must not run");
                });
        assertEquals(4, exit);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains(diagnostic));
    }
}
