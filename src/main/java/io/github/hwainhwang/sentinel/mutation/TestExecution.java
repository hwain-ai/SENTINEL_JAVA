package io.github.hwainhwang.sentinel.mutation;

import java.util.Objects;

/** Evidence captured from one isolated and cache-free test-process execution. */
public record TestExecution(
        ExecutionStatus status,
        String inventorySha256,
        String testId,
        String assertionType,
        String failureSignature,
        String nonce,
        boolean cacheObserved,
        boolean retryObserved) {
    public TestExecution {
        status = Objects.requireNonNull(status, "status");
        inventorySha256 = required(inventorySha256, "inventorySha256");
        testId = Objects.requireNonNull(testId, "testId");
        assertionType = Objects.requireNonNull(assertionType, "assertionType");
        failureSignature = Objects.requireNonNull(failureSignature, "failureSignature");
        nonce = required(nonce, "nonce");
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
