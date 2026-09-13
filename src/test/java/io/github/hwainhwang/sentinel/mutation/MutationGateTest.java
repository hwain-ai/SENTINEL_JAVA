package io.github.hwainhwang.sentinel.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hwainhwang.sentinel.crap.GateThreshold;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MutationGateTest {
    @Test
    void buildsTheExistingEvidenceComponentOnlyWhenEveryCandidateWasKilled() {
        MutationCandidate candidate = candidate("a".repeat(64));
        Map<String, Object> component = MutationGate.component(List.of(
                new MutationRecord(candidate, MutationState.KILLED)));

        assertEquals(1L, component.get("inScope"));
        assertEquals(1L, component.get("killed"));
        assertEquals(0L, component.get("survived"));
        assertEquals(0L, component.get("unauthorizedExclusion"));
        assertTrue((Boolean) component.get("pass"));
        assertEquals(Set.of(
                "inScope", "killed", "survived", "uncovered", "timedOut",
                "compileError", "runtimeError", "pending", "ignored", "toolError",
                "unauthorizedExclusion", "mutationMin", "pass"), component.keySet());
        assertEquals("100", component.get("mutationMin"));
    }

    @Test
    void rejectsZeroCandidatesAndEveryNonKilledState() {
        Map<String, Object> empty = MutationGate.component(List.of());
        Map<String, Object> survivor = MutationGate.component(List.of(
                new MutationRecord(candidate("b".repeat(64)), MutationState.SURVIVED)));

        assertFalse((Boolean) empty.get("pass"));
        assertFalse((Boolean) survivor.get("pass"));
        assertEquals(1L, survivor.get("survived"));
    }

    @Test
    void lowerMinimumKillRateAdmitsSurvivorsUpToTheAllowance() {
        List<MutationRecord> records = List.of(
                new MutationRecord(candidate("a".repeat(64)), MutationState.KILLED),
                new MutationRecord(candidate("b".repeat(64)), MutationState.KILLED),
                new MutationRecord(candidate("c".repeat(64)), MutationState.KILLED),
                new MutationRecord(candidate("d".repeat(64)), MutationState.SURVIVED));

        Map<String, Object> lenient = MutationGate.component(records, GateThreshold.mutationMin("75"));
        Map<String, Object> strict = MutationGate.component(records, GateThreshold.mutationMin("75.01"));
        Map<String, Object> zero = MutationGate.component(List.of(), GateThreshold.mutationMin("0"));

        assertTrue((Boolean) lenient.get("pass"));
        assertEquals("75", lenient.get("mutationMin"));
        assertFalse((Boolean) strict.get("pass"));
        assertFalse((Boolean) zero.get("pass"));
    }

    private static MutationCandidate candidate(String id) {
        return new MutationCandidate(
                id,
                "src/main/java/demo/Flag.java",
                "c".repeat(64),
                5,
                "replace true with false",
                1);
    }
}
