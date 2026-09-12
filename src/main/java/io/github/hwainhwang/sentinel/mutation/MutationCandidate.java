package io.github.hwainhwang.sentinel.mutation;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable candidate identity derived from the pinned backend's ordered scan. */
public record MutationCandidate(
        String id,
        String relativePath,
        String sourceSha256,
        int line,
        String description,
        int ordinal) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public MutationCandidate {
        id = Objects.requireNonNull(id, "id");
        relativePath = Objects.requireNonNull(relativePath, "relativePath");
        sourceSha256 = Objects.requireNonNull(sourceSha256, "sourceSha256");
        description = Objects.requireNonNull(description, "description");
        if (!SHA256.matcher(id).matches() || !SHA256.matcher(sourceSha256).matches()
                || relativePath.isBlank() || description.isBlank() || line < 1 || ordinal < 1) {
            throw new IllegalArgumentException("mutationCandidateInvalid");
        }
    }
}
