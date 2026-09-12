package io.github.hwainhwang.sentinel.mutation;

import io.github.hwainhwang.sentinel.mutation.junit.JUnitReplaySummary;
import io.github.hwainhwang.sentinel.mutation.junit.JUnitRequestWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One fresh compile, optional exact upstream mutation, and authenticated complete JUnit execution. */
record PitReplayExecution(TestExecution execution, String resultsSha256, String inputSha256,
        String originalClassesSha256, String mutantClassSha256, String bindingSha256) {

    static PitReplayExecution run(Map<String, byte[]> sources, PitProbe.Request request,
            PitProbeArtifacts artifacts, PitReport.Result result, String plan, String candidateId,
            boolean mutated, long deadline) throws IOException, InterruptedException {
        try (PitProbeWorkspace workspace = PitProbeWorkspace.create(sources, deadline)) {
            PitProbeCommands.compile(workspace, artifacts, deadline);
            workspace.requireClasses(request.targetClasses(), "target/classes/");
            workspace.requireClasses(request.testClasses(), "target/test-classes/");
            Map<String, byte[]> original = workspace.capturedClasses();
            PitReplayBindings.requireProjectClasses(original);
            String originalHash = PitReplayBindings.contents("sentinel-java-pit-classes-v1", original);
            String mutantHash = apply(workspace, artifacts, original, result, mutated, deadline);
            String input = PitReplayBindings.input(sources, workspace.capturedClasses());
            String binding = MutationHash.digest("sentinel-java-pit-execution-binding-v1",
                    List.of(plan, candidateId, mutated ? "mutant" : "control", input, originalHash, mutantHash));
            JUnitReplaySummary summary = execute(workspace, artifacts, request, binding, deadline);
            return new PitReplayExecution(summary.summary().execution(), summary.resultsSha256(), input,
                    originalHash, mutantHash, binding);
        }
    }

    private static String apply(PitProbeWorkspace workspace, PitProbeArtifacts artifacts, Map<String, byte[]> original,
            PitReport.Result result, boolean mutated, long deadline) throws IOException, InterruptedException {
        if (!mutated) {
            return "";
        }
        byte[] bytes = PitReplayEngine.materialize(artifacts, original, result, deadline);
        workspace.applyMutant(result.identity().mutatedClass(), bytes);
        return PitProbeFiles.sha256(bytes);
    }

    private static JUnitReplaySummary execute(PitProbeWorkspace workspace, PitProbeArtifacts artifacts,
            PitProbe.Request request, String binding, long deadline) throws IOException, InterruptedException {
        var directory = Files.createDirectory(workspace.root().resolve("target/pit-events"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        // RISK(data-loss): workspace cleanup owns this request; do not follow a test-replaced parent to delete it.
        var event = JUnitRequestWriter.create(workspace.root(), directory, binding, false);
        List<String> argv = new ArrayList<>(PitProbeCommands.javaCommand());
        String classpath = PitReplayBindings.runtime() + java.io.File.pathSeparator + artifacts.console()
                + java.io.File.pathSeparator + PitProbeCommands.testClasspath(workspace.root());
        argv.addAll(List.of("-cp", classpath, "io.github.hwainhwang.sentinel.mutation.junit.JUnitReplayMain"));
        argv.addAll(request.testClasses());
        int exit = PitProbeProcess.run(argv, workspace.root(), event.eventFile(), deadline);
        workspace.verify(deadline);
        PitProbeFiles.regular(event.eventFile());
        PitProbeFiles.regular(event.eventFile().resolveSibling(event.eventFile().getFileName() + ".replay"));
        JUnitReplaySummary summary = JUnitReplaySummary.read(event.eventFile(), event.hmacKey());
        requireEvent(summary, event, exit);
        return summary;
    }

    private static void requireEvent(JUnitReplaySummary replay, JUnitRequestWriter.Request request, int exit) {
        var summary = replay.summary();
        TestExecution execution = summary.execution();
        int expectedExit = execution.status() == ExecutionStatus.PASSED ? 0 : 1;
        if (exit != expectedExit || !summary.sourceSha256().equals(request.sourceSha256())
                || !execution.nonce().equals(request.nonce()) || execution.cacheObserved() || execution.retryObserved()) {
            throw new IllegalArgumentException("pitReplayEventMismatch");
        }
    }

    Map<String, Object> output() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("nonce", execution.nonce());
        values.put("status", execution.status().name());
        values.put("inventorySha256", execution.inventorySha256());
        values.put("resultsSha256", resultsSha256);
        values.put("testId", execution.testId());
        values.put("assertionType", execution.assertionType());
        values.put("failureSignature", execution.failureSignature());
        values.put("inputSha256", inputSha256);
        values.put("originalClassesSha256", originalClassesSha256);
        values.put("mutantClassSha256", mutantClassSha256);
        values.put("bindingSha256", bindingSha256);
        return values;
    }
}
