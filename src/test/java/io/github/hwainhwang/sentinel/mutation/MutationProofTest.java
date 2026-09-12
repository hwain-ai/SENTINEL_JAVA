package io.github.hwainhwang.sentinel.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MutationProofTest {
    @Test
    void acceptsKilledOnlyForStableAssertionFailureAcrossTwoFreshReplays() {
        MutationProof proof = new MutationProof(
                passed("control-a"),
                passed("control-b"),
                assertion("mutant-a", "ExactCrapTest#limit", "expected true but was false"),
                assertion("mutant-b", "ExactCrapTest#limit", "expected true but was false"));

        assertEquals(
                MutationState.KILLED,
                MutationNormalizer.normalize(RawMutationStatus.KILLED, proof));
    }

    @Test
    void acceptsTheTypedOpenTest4jAssertionUsedByJunitJupiter() {
        MutationProof proof = new MutationProof(
                passed("control-a"),
                passed("control-b"),
                assertion(
                        "mutant-a",
                        "ExactCrapTest#limit",
                        "expected true but was false",
                        "org.opentest4j.AssertionFailedError"),
                assertion(
                        "mutant-b",
                        "ExactCrapTest#limit",
                        "expected true but was false",
                        "org.opentest4j.AssertionFailedError"));

        assertEquals(
                MutationState.KILLED,
                MutationNormalizer.normalize(RawMutationStatus.KILLED, proof));
    }

    @Test
    void refusesRuntimeErrorsThatTheUpstreamBackendCallsKilled() {
        MutationProof proof = new MutationProof(
                passed("control-a"),
                passed("control-b"),
                runtimeError("mutant-a"),
                runtimeError("mutant-b"));

        assertEquals(
                MutationState.RUNTIME_ERROR,
                MutationNormalizer.normalize(RawMutationStatus.KILLED, proof));
    }

    @Test
    void refusesTimeoutsThatTheUpstreamBackendCallsKilled() {
        MutationProof proof = new MutationProof(
                passed("control-a"),
                passed("control-b"),
                timedOut("mutant-a"),
                timedOut("mutant-b"));

        assertEquals(
                MutationState.TIMED_OUT,
                MutationNormalizer.normalize(RawMutationStatus.KILLED, proof));
    }

    @Test
    void refusesAReplayWhoseAssertionSignatureChanges() {
        MutationProof proof = new MutationProof(
                passed("control-a"),
                passed("control-b"),
                assertion("mutant-a", "ExactCrapTest#limit", "first"),
                assertion("mutant-b", "ExactCrapTest#limit", "second"));

        assertEquals(
                MutationState.RUNTIME_ERROR,
                MutationNormalizer.normalize(RawMutationStatus.KILLED, proof));
    }

    @Test
    void refusesAReplayWhoseAssertionTypeChangesForTheSameTestId() {
        MutationProof proof = new MutationProof(
                passed("control-a"),
                passed("control-b"),
                assertion(
                        "mutant-a",
                        "ExactCrapTest#limit",
                        "same-signature",
                        "java.lang.AssertionError"),
                assertion(
                        "mutant-b",
                        "ExactCrapTest#limit",
                        "same-signature",
                        "org.opentest4j.AssertionFailedError"));

        assertEquals(
                MutationState.RUNTIME_ERROR,
                MutationNormalizer.normalize(RawMutationStatus.KILLED, proof));
    }

    @Test
    void refusesAReusedNonceAcrossControlAndMutantExecutions() {
        MutationProof proof = new MutationProof(
                passed("reused"),
                passed("control-b"),
                assertion("reused", "ExactCrapTest#limit", "same"),
                assertion("mutant-b", "ExactCrapTest#limit", "same"));

        assertEquals(
                MutationState.RUNTIME_ERROR,
                MutationNormalizer.normalize(RawMutationStatus.KILLED, proof));
    }

    @Test
    void refusesAReplayWhenCacheObservationIsNotFresh() {
        MutationProof proof = new MutationProof(
                passed("control-a"),
                passed("control-b"),
                cachedAssertion("mutant-a", "ExactCrapTest#limit", "same"),
                assertion("mutant-b", "ExactCrapTest#limit", "same"));

        assertEquals(
                MutationState.RUNTIME_ERROR,
                MutationNormalizer.normalize(RawMutationStatus.KILLED, proof));
    }

    @Test
    void mapsEveryNonKilledBackendStateWithoutPromotingIt() {
        assertEquals(MutationState.SURVIVED, MutationNormalizer.normalize(RawMutationStatus.SURVIVED, null));
        assertEquals(MutationState.UNCOVERED, MutationNormalizer.normalize(RawMutationStatus.UNCOVERED, null));
        assertEquals(MutationState.TIMED_OUT, MutationNormalizer.normalize(RawMutationStatus.TIMED_OUT, null));
        assertEquals(MutationState.COMPILE_ERROR, MutationNormalizer.normalize(RawMutationStatus.COMPILE_ERROR, null));
        assertEquals(MutationState.RUNTIME_ERROR, MutationNormalizer.normalize(RawMutationStatus.RUNTIME_ERROR, null));
        assertEquals(MutationState.PENDING, MutationNormalizer.normalize(RawMutationStatus.PENDING, null));
        assertEquals(MutationState.IGNORED, MutationNormalizer.normalize(RawMutationStatus.IGNORED, null));
        assertEquals(MutationState.TOOL_ERROR, MutationNormalizer.normalize(RawMutationStatus.TOOL_ERROR, null));
    }

    private static TestExecution passed(String nonce) {
        return new TestExecution(
                ExecutionStatus.PASSED,
                "inventory-sha256",
                "",
                "",
                "",
                nonce,
                false,
                false);
    }

    private static TestExecution assertion(
            String nonce, String testId, String signature) {
        return assertion(nonce, testId, signature, "java.lang.AssertionError");
    }

    private static TestExecution assertion(
            String nonce, String testId, String signature, String assertionType) {
        return new TestExecution(
                ExecutionStatus.ASSERTION_FAILURE,
                "inventory-sha256",
                testId,
                assertionType,
                signature,
                nonce,
                false,
                false);
    }

    private static TestExecution runtimeError(String nonce) {
        return new TestExecution(
                ExecutionStatus.RUNTIME_ERROR,
                "inventory-sha256",
                "ExactCrapTest#limit",
                "NullPointerException",
                "runtime failure",
                nonce,
                false,
                false);
    }

    private static TestExecution cachedAssertion(
            String nonce, String testId, String signature) {
        return new TestExecution(
                ExecutionStatus.ASSERTION_FAILURE,
                "inventory-sha256",
                testId,
                "java.lang.AssertionError",
                signature,
                nonce,
                true,
                false);
    }

    private static TestExecution timedOut(String nonce) {
        return new TestExecution(
                ExecutionStatus.TIMED_OUT,
                "inventory-sha256",
                "",
                "",
                "",
                nonce,
                false,
                false);
    }
}
