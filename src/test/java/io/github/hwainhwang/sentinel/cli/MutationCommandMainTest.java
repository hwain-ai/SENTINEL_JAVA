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
}
