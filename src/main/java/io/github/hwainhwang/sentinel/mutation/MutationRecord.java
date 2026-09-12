package io.github.hwainhwang.sentinel.mutation;

import java.util.Objects;

/** Final SENTINEL state for exactly one admitted backend candidate. */
public record MutationRecord(MutationCandidate candidate, MutationState state) {
    public MutationRecord {
        candidate = Objects.requireNonNull(candidate, "candidate");
        state = Objects.requireNonNull(state, "state");
    }
}
