package io.github.hwainhwang.sentinel.mutation;

/** Status emitted by the mutation backend before proof normalization. */
public enum RawMutationStatus {
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
