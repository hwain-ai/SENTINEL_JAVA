package io.github.hwainhwang.sentinel.mutation;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Converts backend output into a typed result without treating every failure as a kill. */
public final class MutationNormalizer {

    private MutationNormalizer() {
        throw new AssertionError("no instances");
    }

    public static MutationState normalize(RawMutationStatus raw, MutationProof proof) {
        Objects.requireNonNull(raw, "raw");
        if (raw != RawMutationStatus.KILLED) {
            return direct(raw);
        }
        if (proof == null) {
            return MutationState.RUNTIME_ERROR;
        }
        return normalizeClaimedKill(proof);
    }

    private static MutationState direct(RawMutationStatus raw) {
        return MutationState.valueOf(raw.name());
    }

    private static MutationState normalizeClaimedKill(MutationProof proof) {
        if (hasStatus(proof, ExecutionStatus.TIMED_OUT)) {
            return MutationState.TIMED_OUT;
        }
        if (hasStatus(proof, ExecutionStatus.COMPILE_ERROR)) {
            return MutationState.COMPILE_ERROR;
        }
        if (isStableAssertionKill(proof)) {
            return MutationState.KILLED;
        }
        return MutationState.RUNTIME_ERROR;
    }

    private static boolean hasStatus(MutationProof proof, ExecutionStatus status) {
        return proof.controlFirst().status() == status
                || proof.controlSecond().status() == status
                || proof.mutantFirst().status() == status
                || proof.mutantSecond().status() == status;
    }

    private static boolean isStableAssertionKill(MutationProof proof) {
        return controlsPassed(proof)
                && mutantAssertionsMatch(proof)
                && inventoriesMatch(proof)
                && executionsWereFresh(proof);
    }

    private static boolean controlsPassed(MutationProof proof) {
        return proof.controlFirst().status() == ExecutionStatus.PASSED
                && proof.controlSecond().status() == ExecutionStatus.PASSED;
    }

    private static boolean mutantAssertionsMatch(MutationProof proof) {
        TestExecution first = proof.mutantFirst();
        TestExecution second = proof.mutantSecond();
        return first.status() == ExecutionStatus.ASSERTION_FAILURE
                && second.status() == ExecutionStatus.ASSERTION_FAILURE
                && approvedAssertionType(first.assertionType())
                && first.assertionType().equals(second.assertionType())
                && !first.testId().isBlank()
                && first.testId().equals(second.testId())
                && !first.failureSignature().isBlank()
                && first.failureSignature().equals(second.failureSignature());
    }

    /** Shared type allowlist for collectors and normalization; this alone never establishes a kill. */
    // RISK(api): nested JUnit failures must use the same allowlist as top-level proof normalization.
    public static boolean approvedAssertionType(String type) {
        return type.equals("java.lang.AssertionError")
                || type.equals("org.opentest4j.AssertionFailedError")
                || type.equals("org.opentest4j.MultipleFailuresError");
    }

    private static boolean inventoriesMatch(MutationProof proof) {
        String inventory = proof.controlFirst().inventorySha256();
        return inventory.equals(proof.controlSecond().inventorySha256())
                && inventory.equals(proof.mutantFirst().inventorySha256())
                && inventory.equals(proof.mutantSecond().inventorySha256());
    }

    private static boolean executionsWereFresh(MutationProof proof) {
        Set<String> nonces = new HashSet<>();
        return addFresh(nonces, proof.controlFirst())
                && addFresh(nonces, proof.controlSecond())
                && addFresh(nonces, proof.mutantFirst())
                && addFresh(nonces, proof.mutantSecond());
    }

    private static boolean addFresh(Set<String> nonces, TestExecution execution) {
        return !execution.cacheObserved()
                && !execution.retryObserved()
                && nonces.add(execution.nonce());
    }
}
