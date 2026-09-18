package io.github.hwainhwang.sentinel.mutation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Executes every approved Java source through two controls and two backend replays. */
public final class ProjectMutationRunner {
    public MutationRun run(ProjectMutationRequest request) throws Exception {
        if (request == null) {
            throw new IllegalArgumentException("mutationRequestMissing");
        }
        List<ProductionSource> sources = ProductionInventory.load(
                request.projectRoot(), request.inventoryFile());
        List<ProductionSource> targets = targets(sources, request.targets());
        Path events = eventDirectory();
        try (Mutate4JavaAdapter backend = new Mutate4JavaAdapter(request.backendJar())) {
            TypedMavenRunner tests = new TypedMavenRunner(request, events);
            List<MutationRecord> records = execute(request, sources, targets, backend, tests);
            return new MutationRun(records, MutationGate.component(records, request.mutationMin()));
        } finally {
            deleteEventDirectory(events);
            verifyOriginals(request, sources);
        }
    }

    /** The inventory (in order) narrowed to {@code requested}; every requested path must be inventoried. */
    static List<ProductionSource> targets(List<ProductionSource> sources, Set<String> requested) {
        if (requested == null) {
            return sources;
        }
        List<ProductionSource> selected = new ArrayList<>();
        for (ProductionSource source : sources) {
            if (requested.contains(source.relativePath())) {
                selected.add(source);
            }
        }
        if (requested.isEmpty() || selected.size() != requested.size()) {
            throw new IllegalArgumentException("mutationTargetInvalid");
        }
        return List.copyOf(selected);
    }

    private static List<MutationRecord> execute(
            ProjectMutationRequest request,
            List<ProductionSource> sources,
            List<ProductionSource> targets,
            Mutate4JavaAdapter backend,
            TypedMavenRunner tests) throws Exception {
        List<MutationRecord> records = new ArrayList<>();
        for (ProductionSource source : targets) {
            List<MutationCandidate> candidates = scan(
                    request.projectRoot(), sources, source, backend);
            if (!request.lines().isEmpty()) candidates = candidates.stream().filter(candidate -> request.lines().contains(candidate.line())).toList();
            if (!candidates.isEmpty()) {
                records.addAll(runSource(
                        request, sources, source, candidates, backend, tests));
            }
        }
        return List.copyOf(records);
    }

    private static List<MutationCandidate> scan(
            Path projectRoot,
            List<ProductionSource> sources,
            ProductionSource source,
            Mutate4JavaAdapter backend)
            throws Exception {
        try (ProjectSnapshot snapshot = ProjectSnapshot.create(projectRoot)) {
            verifySnapshot(snapshot.root(), sources);
            List<MutationCandidate> candidates = backend.scan(snapshot.root(), source);
            verifySnapshot(snapshot.root(), sources);
            return candidates;
        }
    }

    private static List<MutationRecord> runSource(
            ProjectMutationRequest request,
            List<ProductionSource> sources,
            ProductionSource source,
            List<MutationCandidate> candidates,
            Mutate4JavaAdapter backend,
            TypedMavenRunner tests) throws Exception {
        TestExecution controlFirst = control(request, sources, source, tests);
        TestExecution controlSecond = control(request, sources, source, tests);
        requirePassingControls(controlFirst, controlSecond);
        Mutate4JavaAdapter.Replay first = replay(
                request, sources, source, candidates, controlFirst, backend, tests);
        Mutate4JavaAdapter.Replay second = replay(
                request, sources, source, candidates, controlSecond, backend, tests);
        return normalize(candidates, controlFirst, controlSecond, first, second);
    }

    private static TestExecution control(
            ProjectMutationRequest request,
            List<ProductionSource> sources,
            ProductionSource source,
            TypedMavenRunner tests) throws Exception {
        try (ProjectSnapshot snapshot = ProjectSnapshot.create(request.projectRoot())) {
            verifySnapshot(snapshot.root(), sources);
            MavenModule module = MavenModule.locate(snapshot.root(), source.relativePath());
            TestExecution result = tests.run(
                    module.root(), module.relativeSource(), request.timeoutMillis());
            verifySnapshot(snapshot.root(), sources);
            return result;
        }
    }

    private static Mutate4JavaAdapter.Replay replay(
            ProjectMutationRequest request,
            List<ProductionSource> sources,
            ProductionSource source,
            List<MutationCandidate> candidates,
            TestExecution control,
            Mutate4JavaAdapter backend,
            TypedMavenRunner tests) throws Exception {
        try (ProjectSnapshot snapshot = ProjectSnapshot.create(request.projectRoot())) {
            verifySnapshot(snapshot.root(), sources);
            Mutate4JavaAdapter.Replay replay = backend.replay(
                    snapshot.root(), sources, source, candidates, control, tests);
            verifySnapshot(snapshot.root(), sources);
            return replay;
        }
    }

    private static void requirePassingControls(
            TestExecution first, TestExecution second) {
        if (first.status() != ExecutionStatus.PASSED
                || second.status() != ExecutionStatus.PASSED
                || !first.inventorySha256().equals(second.inventorySha256())
                || first.nonce().equals(second.nonce())) {
            throw new IllegalStateException("mutationControlInvalid");
        }
    }

    private static List<MutationRecord> normalize(
            List<MutationCandidate> candidates,
            TestExecution controlFirst,
            TestExecution controlSecond,
            Mutate4JavaAdapter.Replay first,
            Mutate4JavaAdapter.Replay second) {
        if (!first.candidates().equals(candidates) || !second.candidates().equals(candidates)
                || first.executions().size() != candidates.size()
                || second.executions().size() != candidates.size()) {
            throw new IllegalStateException("mutationCandidateResultSetMismatch");
        }
        List<MutationRecord> records = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            TestExecution mutantFirst = first.executions().get(index);
            TestExecution mutantSecond = second.executions().get(index);
            MutationProof proof = new MutationProof(
                    controlFirst, controlSecond, mutantFirst, mutantSecond);
            records.add(new MutationRecord(
                    candidates.get(index), state(mutantFirst, mutantSecond, proof)));
        }
        return List.copyOf(records);
    }

    private static MutationState state(
            TestExecution first, TestExecution second, MutationProof proof) {
        if (has(first, second, ExecutionStatus.TOOL_ERROR)) {
            return MutationState.TOOL_ERROR;
        }
        if (has(first, second, ExecutionStatus.TIMED_OUT)) {
            return MutationState.TIMED_OUT;
        }
        if (has(first, second, ExecutionStatus.COMPILE_ERROR)) {
            return MutationState.COMPILE_ERROR;
        }
        if (first.status() == ExecutionStatus.PASSED
                && second.status() == ExecutionStatus.PASSED) {
            return MutationState.SURVIVED;
        }
        return MutationNormalizer.normalize(RawMutationStatus.KILLED, proof);
    }

    private static boolean has(
            TestExecution first, TestExecution second, ExecutionStatus status) {
        return first.status() == status || second.status() == status;
    }

    private static void verifySnapshot(Path root, List<ProductionSource> sources)
            throws IOException {
        Set<String> expected = new HashSet<>();
        for (ProductionSource source : sources) {
            expected.add(source.relativePath());
            if (!exactSnapshotSource(root, source)) {
                throw new IllegalStateException("snapshotSourceIdentityChanged");
            }
        }
        if (!ProductionInventory.discoverProduction(root).equals(expected)) {
            throw new IllegalStateException("snapshotSourceIdentityChanged");
        }
    }

    private static boolean exactSnapshotSource(Path root, ProductionSource source)
            throws IOException {
        Path path = root.resolve(source.relativePath());
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Number links = (Number) Files.getAttribute(
                path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        return links.longValue() == 1L
                && ProductionInventory.sha256(path).equals(source.sha256());
    }

    private static void verifyOriginals(
            ProjectMutationRequest request, List<ProductionSource> sources)
            throws IOException {
        Path root = request.projectRoot();
        for (ProductionSource source : sources) {
            Path path = root.resolve(source.relativePath());
            if (Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !ProductionInventory.sha256(path).equals(source.sha256())) {
                throw new IllegalStateException("originalSourceIdentityChanged");
            }
        }
        if (!ProductionInventory.load(root, request.inventoryFile()).equals(sources)) {
            throw new IllegalStateException("originalSourceInventoryChanged");
        }
    }

    private static Path eventDirectory() throws IOException {
        // macOS keeps temporary files under a symlinked /var; runners compare real paths.
        Path result = Files.createTempDirectory("sentinel-java-events-").toRealPath();
        Files.setPosixFilePermissions(
                result, PosixFilePermissions.fromString("rwx------"));
        return result;
    }

    private static void deleteEventDirectory(Path directory) throws IOException {
        if (directory == null || directory.getFileName() == null
                || !directory.getFileName().toString().startsWith("sentinel-java-events-")) {
            throw new IllegalArgumentException("eventCleanupRootInvalid");
        }
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
