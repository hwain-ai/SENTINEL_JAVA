package io.github.hwainhwang.sentinel.mutation;

import java.util.Objects;
import java.util.regex.Pattern;

/** One explicitly approved production Java source and its pre-run byte identity. */
public record ProductionSource(String relativePath, String sha256) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public ProductionSource {
        relativePath = Objects.requireNonNull(relativePath, "relativePath");
        sha256 = Objects.requireNonNull(sha256, "sha256");
        if (relativePath.isBlank() || !SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("productionSourceInvalid");
        }
    }
}
