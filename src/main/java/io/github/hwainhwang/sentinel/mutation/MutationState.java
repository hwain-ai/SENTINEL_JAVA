package io.github.hwainhwang.sentinel.mutation;

/** Stable SENTINEL result state exposed to gates and reports. */
public enum MutationState {
    KILLED,
    SURVIVED,
    UNCOVERED,
    TIMED_OUT,
    COMPILE_ERROR,
    RUNTIME_ERROR,
    PENDING,
    IGNORED,
    TOOL_ERROR
}
