package io.github.hwainhwang.sentinel.mutation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.SourceVersion;

/** Explicit, non-certifying Java 17/JUnit 5 PIT experiment. */
public final class PitProbe {
    private PitProbe() {
        throw new AssertionError("no instances");
    }

    public record Request(Path projectRoot, Path artifactRoot,
            List<String> targetClasses, List<String> testClasses, long timeoutMillis) { }

    public static Map<String, Object> run(Request request) throws IOException, InterruptedException {
        validate(request);
        request = new Request(request.projectRoot(), request.artifactRoot(), List.copyOf(request.targetClasses()),
                List.copyOf(request.testClasses()), request.timeoutMillis());
        long deadline = System.nanoTime() + request.timeoutMillis() * 1_000_000;
        PitProbeProcess.check(deadline);
        PitProbeArtifacts artifacts = PitProbeArtifacts.verify(request.artifactRoot());
        String runnerFingerprint = PitReplayBindings.runtimeFingerprint();
        Map<String, byte[]> sources = PitProbeFiles.capture(request.projectRoot(), deadline);
        PitReport discovery = execute(request, artifacts, sources, true, deadline);
        PitReport result = execute(request, artifacts, sources, false, deadline);
        result.joinInventory(identities(discovery));
        Map<String, Object> output = new LinkedHashMap<>(report(artifacts, request, sources, result));
        String plan = MutationHash.digest("sentinel-java-pit-replay-plan-v1", List.of(
                (String) output.get("planSha256"), runnerFingerprint, PitReplayBindings.PROFILE, inventory(result)));
        output.put("planSha256", plan);
        output.put("replayProfile", PitReplayBindings.PROFILE);
        output.put("runnerSha256", runnerFingerprint);
        output.put("replays", PitReplays.collect(sources, request, artifacts, result, plan, deadline));
        if (!runnerFingerprint.equals(PitReplayBindings.runtimeFingerprint())) {
            throw new IllegalArgumentException("pitReplayRuntimeChanged");
        }
        PitProbeArtifacts.verify(request.artifactRoot());
        PitProbeFiles.verify(request.projectRoot(), sources, deadline);
        return output;
    }

    private static String inventory(PitReport report) {
        List<String> values = new ArrayList<>();
        for (PitReport.Result result : report.results()) {
            var id = result.identity();
            values.add(MutationHash.digest("sentinel-java-pit-candidate-v1", List.of(id.mutatedClass(),
                    id.mutatedMethod(), id.methodDescription(), id.mutator(), id.indexes().toString())));
        }
        values.sort(String::compareTo);
        return MutationHash.digest("sentinel-java-pit-inventory-v1", values);
    }

    private static void validate(Request request) {
        if (request == null || request.timeoutMillis() < 1 || request.timeoutMillis() > 3_600_000) {
            throw new IllegalArgumentException("pitProbeRequestInvalid");
        }
        validateClasses(request.targetClasses());
        validateClasses(request.testClasses());
    }

    private static void validateClasses(List<String> names) {
        if (names == null || names.isEmpty() || names.size() > 64 || new HashSet<>(names).size() != names.size()) {
            throw new IllegalArgumentException("pitProbeRequestInvalid");
        }
        for (String name : names) {
            if (name == null || !SourceVersion.isName(name)) {
                throw new IllegalArgumentException("pitProbeRequestInvalid");
            }
        }
    }

    private static PitReport execute(Request request, PitProbeArtifacts artifacts,
            Map<String, byte[]> sources, boolean discovery, long deadline) throws IOException, InterruptedException {
        try (PitProbeWorkspace workspace = PitProbeWorkspace.create(sources, deadline)) {
            PitProbeCommands.compile(workspace, artifacts, deadline);
            workspace.requireClasses(request.targetClasses(), "target/classes/");
            workspace.requireClasses(request.testClasses(), "target/test-classes/");
            if (discovery) {
                PitProbeCommands.baseline(workspace, artifacts, request.testClasses(), deadline);
            }
            return PitProbeCommands.execute(workspace, artifacts, request, discovery, deadline);
        }
    }

    private static List<PitReport.Identity> identities(PitReport discovery) {
        List<PitReport.Identity> identities = new ArrayList<>();
        for (PitReport.Result result : discovery.results()) {
            if (result.status() != PitReport.Status.NOT_STARTED && result.status() != PitReport.Status.NO_COVERAGE) {
                throw new IllegalArgumentException("pitProbeDiscoveryInvalid");
            }
            identities.add(result.identity());
        }
        return List.copyOf(identities);
    }

    private static Map<String, Object> report(PitProbeArtifacts artifacts, Request request,
            Map<String, byte[]> sources, PitReport report) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PitReport.Status status : PitReport.Status.values()) {
            counts.put(status.name(), 0);
        }
        List<Map<String, Object>> mutants = new ArrayList<>();
        for (PitReport.Result result : report.results()) {
            mutants.add(mutant(sources, result));
            counts.put(result.status().name(), counts.get(result.status().name()) + 1);
        }
        mutants.sort(PitProbe::compareMutants);
        return Map.of("schemaVersion", "sentinel-java-pit-probe-v1", "backend", "PIT",
                "version", artifacts.version(), "profile", "java17-junit5-explicit-v1",
                "certified", false, "discoveryVerified", true, "backendCounts", counts, "mutants", mutants,
                "admission", "backendNotAdmitted", "planSha256", planFingerprint(artifacts, request, sources));
    }

    private static int compareMutants(Map<String, Object> left, Map<String, Object> right) {
        return ((String) left.get("id")).compareTo((String) right.get("id"));
    }

    private static String planFingerprint(PitProbeArtifacts artifacts, Request request, Map<String, byte[]> sources) {
        List<String> values = new ArrayList<>(List.of(artifacts.fingerprint(), "java17-junit5-explicit-v1",
                PitProbeCommands.MUTATORS, request.targetClasses().toString(), request.testClasses().toString()));
        List<String> paths = new ArrayList<>(sources.keySet());
        paths.sort(String::compareTo);
        for (String path : paths) {
            values.add(path);
            values.add(PitProbeFiles.sha256(sources.get(path)));
        }
        return MutationHash.digest("sentinel-java-pit-plan-v1", values);
    }

    static Map<String, Object> mutant(Map<String, byte[]> sources, PitReport.Result result) {
        PitReport.Identity identity = result.identity();
        String packagePath = identity.mutatedClass().substring(0, identity.mutatedClass().lastIndexOf('.') + 1)
                .replace('.', '/');
        String source = "src/main/java/" + packagePath + result.sourceFile();
        if (!sources.containsKey(source)) {
            throw new IllegalArgumentException("pitProbeResultSourceMissing");
        }
        String sourceHash = PitProbeFiles.sha256(sources.get(source));
        String id = MutationHash.digest("sentinel-java-pit-probe-v1", List.of(sourceHash,
                identity.mutatedClass(), identity.mutatedMethod(), identity.methodDescription(),
                identity.mutator(), identity.indexes().toString()));
        return Map.of("id", id, "sourceFile", source, "sourceSha256", sourceHash, "line", result.lineNumber(),
                "mutatedClass", identity.mutatedClass(), "mutatedMethod", identity.mutatedMethod(),
                "methodDescription", identity.methodDescription(), "mutator", identity.mutator(),
                "indexes", identity.indexes(), "backendStatus", result.status().name());
    }
}
