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
    TOOL_ERROR;

    public String wireName() {
        return switch (this) {
            case TIMED_OUT -> "timedOut";
            case COMPILE_ERROR -> "compileError";
            case RUNTIME_ERROR -> "runtimeError";
            case TOOL_ERROR -> "toolError";
            default -> name().toLowerCase(java.util.Locale.ROOT);
        };
    }
}
