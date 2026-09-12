package io.github.hwainhwang.sentinel.mutation;

import java.util.Objects;

/** Two clean controls and two fresh mutant replays used to prove a kill. */
public record MutationProof(
        TestExecution controlFirst,
        TestExecution controlSecond,
        TestExecution mutantFirst,
        TestExecution mutantSecond) {
    public MutationProof {
        controlFirst = Objects.requireNonNull(controlFirst, "controlFirst");
        controlSecond = Objects.requireNonNull(controlSecond, "controlSecond");
        mutantFirst = Objects.requireNonNull(mutantFirst, "mutantFirst");
        mutantSecond = Objects.requireNonNull(mutantSecond, "mutantSecond");
    }
}
