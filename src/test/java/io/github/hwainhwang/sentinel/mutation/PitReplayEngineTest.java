package io.github.hwainhwang.sentinel.mutation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PitReplayEngineTest {
    @TempDir Path directory;

    @Test
    void materializesOnlyTheExactUpstreamIdentityAndRestoresTheContextLoader() throws Exception {
        Path project = PitProbeTest.fixture(directory.resolve("project"), "assertTrue(new Value().positive(1));");
        var request = PitProbeTest.request(project);
        var artifacts = PitProbeArtifacts.verify(request.artifactRoot());
        long deadline = System.nanoTime() + 60_000_000_000L;
        var sources = PitProbeFiles.capture(project, deadline);
        try (var workspace = PitProbeWorkspace.create(sources, deadline)) {
            PitProbeCommands.compile(workspace, artifacts, deadline);
            var report = PitProbeCommands.execute(workspace, artifacts, request, true, deadline);
            var result = report.results().get(0);
            var classes = workspace.capturedClasses();
            String path = "target/classes/" + result.identity().mutatedClass().replace('.', '/') + ".class";
            byte[] original = classes.get(path).clone();
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            try (var hostile = new java.net.URLClassLoader(new java.net.URL[0], null)) {
                Thread.currentThread().setContextClassLoader(hostile);
                byte[] first = PitReplayEngine.materialize(artifacts, classes, result, deadline);
                assertSame(hostile, Thread.currentThread().getContextClassLoader());
                assertArrayEquals(first, PitReplayEngine.materialize(artifacts, classes, result, deadline));
                assertArrayEquals(original, classes.get(path));
                assertNotEquals(PitProbeFiles.sha256(original), PitProbeFiles.sha256(first));
                for (PitReport.Result invalid : List.of(copy(result, result.identity().indexes(), "Wrong.java", result.lineNumber()),
                        copy(result, result.identity().indexes(), result.sourceFile(), result.lineNumber() + 1),
                        copy(result, List.of(999999), result.sourceFile(), result.lineNumber()),
                        copy(result, List.of(0, 1), result.sourceFile(), result.lineNumber()))) {
                    assertThrows(IllegalArgumentException.class,
                            () -> PitReplayEngine.materialize(artifacts, classes, invalid, deadline));
                    assertSame(hostile, Thread.currentThread().getContextClassLoader());
                }
                assertEquals("pitProbeTimedOut", assertThrows(IllegalArgumentException.class,
                        () -> PitReplayEngine.materialize(artifacts, classes, result, System.nanoTime() - 1)).getMessage());
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
    }

    private static PitReport.Result copy(PitReport.Result source, List<Integer> indexes, String file, int line) {
        var identity = source.identity();
        return new PitReport.Result(new PitReport.Identity(identity.mutatedClass(), identity.mutatedMethod(),
                identity.methodDescription(), identity.mutator(), indexes), file, line, source.blocks(),
                source.description(), source.killingTest(), source.numberOfTestsRun(), source.status());
    }
}
