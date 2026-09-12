package io.github.hwainhwang.sentinel.mutation.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.hwainhwang.sentinel.mutation.ExecutionStatus;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.MultipleFailuresError;
import java.util.List;

class JUnitTypedListenerTest {
    @Test
    void classifiesOpenTest4jFailuresAsAssertions() {
        assertEquals(
                ExecutionStatus.ASSERTION_FAILURE,
                SentinelTestExecutionListener.classify(
                        new AssertionFailedError("expected", true, false)));
    }

    @Test
    void classifiesNonAssertionThrowablesAsRuntimeErrors() {
        assertEquals(
                ExecutionStatus.RUNTIME_ERROR,
                SentinelTestExecutionListener.classify(
                        new IllegalStateException("runner failed")));
    }

    @Test
    void keepsAggregateRuntimeFailuresOutOfAssertionEvidence() {
        assertEquals(ExecutionStatus.RUNTIME_ERROR, SentinelTestExecutionListener.classify(
                new MultipleFailuresError("mixed", List.of(new AssertionError("assertion"),
                        new IllegalStateException("runtime")))));
        assertEquals(ExecutionStatus.ASSERTION_FAILURE, SentinelTestExecutionListener.classify(
                new MultipleFailuresError("assertions", List.of(new AssertionError("first"),
                        new AssertionFailedError("second")))));
    }

    @Test
    void refusesUnsupportedAssertionTypesInsideApprovedAggregates() {
        assertEquals(ExecutionStatus.RUNTIME_ERROR, SentinelTestExecutionListener.classify(
                new MultipleFailuresError("wrapped", List.of(new UnsupportedAssertion()))));
    }

    @Test
    void refusesEmptyMissingAndExcessivelyNestedFailures() {
        assertEquals(ExecutionStatus.RUNTIME_ERROR, SentinelTestExecutionListener.classify(null));
        assertEquals(ExecutionStatus.RUNTIME_ERROR,
                SentinelTestExecutionListener.classify(new MultipleFailuresError("empty", List.of())));
        Throwable failure = new AssertionError("leaf");
        for (int index = 0; index < 18; index++) {
            failure = new MultipleFailuresError("nested", List.of(failure));
        }
        assertEquals(ExecutionStatus.RUNTIME_ERROR, SentinelTestExecutionListener.classify(failure));
    }

    static class UnsupportedAssertion extends AssertionError { }
}
