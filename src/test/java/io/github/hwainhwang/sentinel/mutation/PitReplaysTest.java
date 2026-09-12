package io.github.hwainhwang.sentinel.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class PitReplaysTest {
    @Test
    void distinguishesStableKillsSurvivorsAndUnprovenBackendObservations() {
        var a = execution(false, ExecutionStatus.PASSED, "a", "inventory", "results", "source", "base", "", "control", "");
        var b = execution(false, ExecutionStatus.PASSED, "b", "inventory", "results", "source", "base", "", "control", "");
        var c = mutant("c");
        var d = mutant("d");
        assertEquals(MutationState.KILLED, PitReplays.classify(PitReport.Status.KILLED, a, b, c, d));
        for (PitReport.Status status : PitReport.Status.values()) {
            if (status != PitReport.Status.KILLED) {
                assertEquals(MutationState.TOOL_ERROR, PitReplays.classify(status, a, b, c, d));
            }
        }
        var passedC = execution(true, ExecutionStatus.PASSED, "c", "inventory", "results", "mutant", "base", "f".repeat(64), "mutant", "");
        var passedD = execution(true, ExecutionStatus.PASSED, "d", "inventory", "results", "mutant", "base", "f".repeat(64), "mutant", "");
        assertEquals(MutationState.SURVIVED, PitReplays.classify(PitReport.Status.SURVIVED, a, b, passedC, passedD));
    }

    @Test
    void rejectsEveryCrossReplayBindingMismatch() {
        var a = execution(false, ExecutionStatus.PASSED, "a", "inventory", "results", "source", "base", "", "control", "");
        var b = execution(false, ExecutionStatus.PASSED, "b", "inventory", "results", "source", "base", "", "control", "");
        var c = mutant("c");
        for (PitReplayExecution changed : List.of(
                execution(true, ExecutionStatus.RUNTIME_ERROR, "d", "inventory", "results", "mutant", "base", "f".repeat(64), "mutant", "failure"),
                execution(true, ExecutionStatus.ASSERTION_FAILURE, "d", "inventory", "other", "mutant", "base", "f".repeat(64), "mutant", "failure"),
                execution(true, ExecutionStatus.ASSERTION_FAILURE, "d", "inventory", "results", "other", "base", "f".repeat(64), "mutant", "failure"),
                execution(true, ExecutionStatus.ASSERTION_FAILURE, "d", "inventory", "results", "mutant", "base", "e".repeat(64), "mutant", "failure"),
                execution(true, ExecutionStatus.ASSERTION_FAILURE, "d", "inventory", "results", "mutant", "base", "f".repeat(64), "other", "failure"),
                execution(true, ExecutionStatus.ASSERTION_FAILURE, "d", "inventory", "results", "mutant", "base", "f".repeat(64), "mutant", "other"),
                execution(true, ExecutionStatus.ASSERTION_FAILURE, "d", "other", "results", "mutant", "base", "f".repeat(64), "mutant", "failure"),
                execution(true, ExecutionStatus.ASSERTION_FAILURE, "d", "inventory", "results", "mutant", "other", "f".repeat(64), "mutant", "failure"), mutant("a"))) {
            assertEquals(MutationState.TOOL_ERROR, PitReplays.classify(PitReport.Status.KILLED, a, b, c, changed));
        }
        assertEquals(MutationState.TOOL_ERROR, PitReplays.classify(PitReport.Status.KILLED, c, b, c, mutant("d")));
    }

    private static PitReplayExecution mutant(String nonce) {
        return execution(true, ExecutionStatus.ASSERTION_FAILURE, nonce, "inventory", "results", "mutant",
                "base", "f".repeat(64), "mutant", "failure");
    }

    private static PitReplayExecution execution(boolean mutant, ExecutionStatus status, String nonce,
            String inventory, String results, String input, String base, String mutantHash, String binding, String failure) {
        return new PitReplayExecution(new TestExecution(status, inventory, mutant ? "test" : "",
                mutant ? "org.opentest4j.AssertionFailedError" : "", failure, nonce, false, false),
                results, input, base, mutantHash, binding);
    }
}
