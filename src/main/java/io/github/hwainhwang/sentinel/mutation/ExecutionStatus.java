package io.github.hwainhwang.sentinel.mutation;

/** Outcome observed from one fresh test-process execution. */
public enum ExecutionStatus {
    PASSED,
    ASSERTION_FAILURE,
    RUNTIME_ERROR,
    COMPILE_ERROR,
    TIMED_OUT,
    TOOL_ERROR
}
