package io.github.hwainhwang.sentinel.mutation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Candidate-bound replay checks stay separate from backend observations and operational admission. */
final class PitReplays {
    private PitReplays() {
        throw new AssertionError("no instances");
    }

    static List<Map<String, Object>> collect(Map<String, byte[]> sources, PitProbe.Request request,
            PitProbeArtifacts artifacts, PitReport results, String plan, long deadline)
            throws IOException, InterruptedException {
        List<Map<String, Object>> records = new ArrayList<>();
        for (PitReport.Result result : results.results()) {
            String id = (String) PitProbe.mutant(sources, result).get("id");
            var controlFirst = PitReplayExecution.run(sources, request, artifacts, result, plan, id, false, deadline);
            var mutantFirst = PitReplayExecution.run(sources, request, artifacts, result, plan, id, true, deadline);
            var controlSecond = PitReplayExecution.run(sources, request, artifacts, result, plan, id, false, deadline);
            var mutantSecond = PitReplayExecution.run(sources, request, artifacts, result, plan, id, true, deadline);
            MutationState status = classify(result.status(), controlFirst, controlSecond, mutantFirst, mutantSecond);
            records.add(Map.of("candidateId", id, "status", status.name(),
                    "controlFirst", controlFirst.output(), "controlSecond", controlSecond.output(),
                    "mutantFirst", mutantFirst.output(), "mutantSecond", mutantSecond.output()));
        }
        records.sort(PitReplays::compareRecords);
        return List.copyOf(records);
    }

    private static int compareRecords(Map<String, Object> left, Map<String, Object> right) {
        return ((String) left.get("candidateId")).compareTo((String) right.get("candidateId"));
    }

    static MutationState classify(PitReport.Status raw, PitReplayExecution a, PitReplayExecution b,
            PitReplayExecution c, PitReplayExecution d) {
        if (!controls(a, b) || !consistent(c, d) || !bindings(a, b, c, d)) {
            return MutationState.TOOL_ERROR;
        }
        MutationProof proof = new MutationProof(a.execution(), b.execution(), c.execution(), d.execution());
        if (raw == PitReport.Status.KILLED) {
            return MutationNormalizer.normalize(RawMutationStatus.KILLED, proof);
        }
        if (raw == PitReport.Status.SURVIVED && c.execution().status() == ExecutionStatus.PASSED) {
            return MutationState.SURVIVED;
        }
        return MutationState.TOOL_ERROR;
    }

    private static boolean controls(PitReplayExecution first, PitReplayExecution second) {
        return first.execution().status() == ExecutionStatus.PASSED
                && second.execution().status() == ExecutionStatus.PASSED && consistent(first, second)
                && first.mutantClassSha256().isEmpty() && second.mutantClassSha256().isEmpty();
    }

    private static boolean consistent(PitReplayExecution first, PitReplayExecution second) {
        return first.execution().status() == second.execution().status()
                && first.resultsSha256().equals(second.resultsSha256())
                && first.inputSha256().equals(second.inputSha256())
                && first.mutantClassSha256().equals(second.mutantClassSha256())
                && first.bindingSha256().equals(second.bindingSha256())
                && first.execution().failureSignature().equals(second.execution().failureSignature());
    }

    private static boolean bindings(PitReplayExecution a, PitReplayExecution b, PitReplayExecution c, PitReplayExecution d) {
        var nonces = new HashSet<String>();
        for (PitReplayExecution execution : List.of(a, b, c, d)) {
            if (!execution.originalClassesSha256().equals(a.originalClassesSha256())
                    || !execution.execution().inventorySha256().equals(a.execution().inventorySha256())
                    || !nonces.add(execution.execution().nonce())) {
                return false;
            }
        }
        return !a.inputSha256().equals(c.inputSha256()) && c.mutantClassSha256().matches("[0-9a-f]{64}");
    }
}
