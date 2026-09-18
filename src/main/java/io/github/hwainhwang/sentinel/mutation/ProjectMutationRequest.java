package io.github.hwainhwang.sentinel.mutation;

import io.github.hwainhwang.sentinel.crap.GateThreshold;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.List;

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
        GateThreshold mutationMin,
        Set<String> targets,
        Set<Integer> lines,
        List<String> tests) {
    public ProjectMutationRequest(Path projectRoot, Path inventoryFile, Path backendJar,
            Path javaHome, Path mavenHome, Path mavenRepository, Path listenerPath,
            long timeoutMillis, GateThreshold mutationMin, Set<String> targets) {
        this(projectRoot, inventoryFile, backendJar, javaHome, mavenHome, mavenRepository,
                listenerPath, timeoutMillis, mutationMin, targets, Set.of(), List.of());
    }
    /** {@code targets} names the inventory paths to mutate; {@code null} mutates all of them. */
    public ProjectMutationRequest(
            Path projectRoot,
            Path inventoryFile,
            Path backendJar,
            Path javaHome,
            Path mavenHome,
            Path mavenRepository,
            Path listenerPath,
            long timeoutMillis,
            GateThreshold mutationMin) {
        this(projectRoot, inventoryFile, backendJar, javaHome, mavenHome, mavenRepository,
                listenerPath, timeoutMillis, mutationMin, null);
    }

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
        targets = targets == null ? null : Set.copyOf(targets);
        lines = Set.copyOf(lines);
        tests = List.copyOf(tests);
        validateSelection(lines, targets);
        if (timeoutMillis != 0 && (timeoutMillis < 1_000L || timeoutMillis > 3_600_000L)) {
            throw new IllegalArgumentException("mutationTimeoutInvalid");
        }
    }

    private static void validateSelection(Set<Integer> lines, Set<String> targets) {
        for (int line : lines) {
            if (line < 1) {
                throw new IllegalArgumentException("functionSelectionInvalid");
            }
        }
        if (!lines.isEmpty()) {
            requireSingleTarget(targets);
        }
    }

    private static void requireSingleTarget(Set<String> targets) {
        if (targets == null || targets.size() != 1) {
            throw new IllegalArgumentException("functionSelectionInvalid");
        }
    }
}
