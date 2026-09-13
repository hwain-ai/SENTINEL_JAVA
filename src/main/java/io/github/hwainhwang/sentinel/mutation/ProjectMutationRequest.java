package io.github.hwainhwang.sentinel.mutation;

import io.github.hwainhwang.sentinel.crap.GateThreshold;
import java.nio.file.Path;
import java.util.Objects;

/** Explicit filesystem and toolchain inputs for one harness-neutral mutation run. */
public record ProjectMutationRequest(
        Path projectRoot,
        Path inventoryFile,
        Path backendJar,
        Path javaHome,
        Path mavenHome,
        Path mavenRepository,
        Path listenerPath,
        long timeoutMillis,
        GateThreshold mutationMin) {
    public ProjectMutationRequest(
            Path projectRoot,
            Path inventoryFile,
            Path backendJar,
            Path javaHome,
            Path mavenHome,
            Path mavenRepository,
            Path listenerPath,
            long timeoutMillis) {
        this(projectRoot, inventoryFile, backendJar, javaHome, mavenHome, mavenRepository,
                listenerPath, timeoutMillis, GateThreshold.DEFAULT_MUTATION_MIN);
    }

    public ProjectMutationRequest {
        projectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
        inventoryFile = Objects.requireNonNull(inventoryFile, "inventoryFile");
        backendJar = Objects.requireNonNull(backendJar, "backendJar");
        javaHome = Objects.requireNonNull(javaHome, "javaHome");
        mavenHome = Objects.requireNonNull(mavenHome, "mavenHome");
        mavenRepository = Objects.requireNonNull(mavenRepository, "mavenRepository");
        listenerPath = Objects.requireNonNull(listenerPath, "listenerPath");
        mutationMin = Objects.requireNonNull(mutationMin, "mutationMin");
        if (timeoutMillis < 1_000L || timeoutMillis > 3_600_000L) {
            throw new IllegalArgumentException("mutationTimeoutInvalid");
        }
    }
}
