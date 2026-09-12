package io.github.hwainhwang.sentinel.mutation;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Project-wide exact result records plus the shared evidence component. */
public record MutationRun(
        List<MutationRecord> records,
        Map<String, Object> evidenceComponent) {
    public MutationRun {
        if (records == null || evidenceComponent == null) {
            throw new IllegalArgumentException("mutationRunInvalid");
        }
        records = List.copyOf(records);
        evidenceComponent = Map.copyOf(evidenceComponent);
        Set<String> ids = new HashSet<>();
        for (MutationRecord record : records) {
            if (record == null || !ids.add(record.candidate().id())) {
                throw new IllegalArgumentException("mutationCandidateSetInvalid");
            }
        }
    }
}
