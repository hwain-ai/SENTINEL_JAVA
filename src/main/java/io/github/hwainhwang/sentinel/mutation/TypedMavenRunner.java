package io.github.hwainhwang.sentinel.mutation;

import io.github.hwainhwang.sentinel.mutation.junit.JUnitEventSummary;
import io.github.hwainhwang.sentinel.mutation.junit.JUnitRequestWriter;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs Maven through a fixed argv and accepts only the typed JUnit listener event. */
final class TypedMavenRunner {
    private static final String EMPTY_INVENTORY = "0".repeat(64);
    private final Path javaHome;
    private final Path mavenHome;
    private final Path mavenRepository;
    private final Path listenerPath;
    private final Path eventDirectory;
    private final long maximumTimeoutMillis;

    TypedMavenRunner(ProjectMutationRequest request, Path eventDirectory) throws IOException {
        this.javaHome = canonicalDirectory(request.javaHome(), "javaHomeInvalid");
        this.mavenHome = canonicalDirectory(request.mavenHome(), "mavenHomeInvalid");
        this.mavenRepository = canonicalDirectory(
                request.mavenRepository(), "mavenRepositoryInvalid");
        this.listenerPath = canonicalListener(request.listenerPath());
        this.eventDirectory = canonicalDirectory(eventDirectory, "eventDirectoryInvalid");
        this.maximumTimeoutMillis = request.timeoutMillis();
        executable(javaHome.resolve("bin/java"), "javaExecutableInvalid");
        executable(mavenHome.resolve("bin/mvn"), "mavenExecutableInvalid");
    }

    TestExecution run(Path projectRoot, String relativeSource, long requestedTimeoutMillis)
            throws IOException, InterruptedException {
        boolean cacheObserved = prepareFreshGeneratedOutput(projectRoot);
        Path source = projectRoot.resolve(relativeSource);
        String sourceSha256 = ProductionInventory.sha256(source);
        long timeoutMillis = timeout(requestedTimeoutMillis);
        try (JUnitRequestWriter.Request request = JUnitRequestWriter.create(
                projectRoot, eventDirectory, sourceSha256, cacheObserved)) {
            Process process = process(projectRoot).start();
            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                terminate(process);
                return timedOut(request);
            }
            return event(request, process.exitValue());
        }
    }

    long backendBaselineDurationMillis() {
        return Math.max(1L, maximumTimeoutMillis / 10L);
    }

    private ProcessBuilder process(Path projectRoot) throws IOException {
        Path home = projectRoot.resolve("target/sentinel-home");
        Files.createDirectories(home);
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwx------"));
        List<String> argv = new ArrayList<>();
        argv.add(mavenHome.resolve("bin/mvn").toString());
        argv.add("-o");
        argv.add("-B");
        argv.add("-ntp");
        argv.add("-Dmaven.repo.local=" + mavenRepository);
        argv.add("-Dmaven.test.additionalClasspath=" + listenerPath);
        argv.add("-DexcludedGroups=no-mutate");
        argv.add("test");
        ProcessBuilder builder = new ProcessBuilder(argv)
                .directory(projectRoot.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("HOME", home.toString());
        environment.put("LANG", "C.UTF-8");
        environment.put("LC_ALL", "C.UTF-8");
        environment.put("PATH", javaHome.resolve("bin") + ":"
                + mavenHome.resolve("bin") + ":/usr/bin:/bin");
        environment.put("JAVA_HOME", javaHome.toString());
        environment.put("MAVEN_HOME", mavenHome.toString());
        environment.put("MAVEN_BASEDIR", projectRoot.toString());
        environment.put("MAVEN_SKIP_RC", "true");
        return builder;
    }

    private TestExecution event(JUnitRequestWriter.Request request, int exitCode)
            throws IOException {
        if (!Files.isRegularFile(request.eventFile(), LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(request.eventFile())) {
            return toolError(request);
        }
        JUnitEventSummary summary = JUnitEventSummary.read(
                request.eventFile(), request.hmacKey());
        TestExecution execution = summary.execution();
        if (!summary.sourceSha256().equals(request.sourceSha256())
                || !execution.nonce().equals(request.nonce())
                || exitMismatch(execution.status(), exitCode)) {
            return toolError(request);
        }
        return execution;
    }

    private static boolean prepareFreshGeneratedOutput(Path projectRoot) throws IOException {
        Path target = projectRoot.resolve("target");
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (Files.isSymbolicLink(target)
                || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("mutationCacheObservationUnknown");
        }
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                requireDisposableCacheFile(file, attributes);
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
        return false;
    }

    private static void requireDisposableCacheFile(
            Path file, BasicFileAttributes attributes) throws IOException {
        if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
            throw new IllegalArgumentException("mutationCacheObservationUnknown");
        }
        Number links = (Number) Files.getAttribute(
                file, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        if (links.longValue() != 1L) {
            throw new IllegalArgumentException("mutationCacheObservationUnknown");
        }
    }

    private static boolean exitMismatch(ExecutionStatus status, int exitCode) {
        return status == ExecutionStatus.PASSED ? exitCode != 0 : exitCode == 0;
    }

    private static TestExecution timedOut(JUnitRequestWriter.Request request) {
        return execution(ExecutionStatus.TIMED_OUT, request);
    }

    private static TestExecution toolError(JUnitRequestWriter.Request request) {
        return execution(ExecutionStatus.TOOL_ERROR, request);
    }

    private static TestExecution execution(
            ExecutionStatus status, JUnitRequestWriter.Request request) {
        return new TestExecution(
                status,
                EMPTY_INVENTORY,
                "",
                "",
                "",
                request.nonce(),
                request.cacheObserved(),
                false);
    }

    private long timeout(long requested) {
        if (requested <= 0L) {
            return maximumTimeoutMillis;
        }
        return Math.min(requested, maximumTimeoutMillis);
    }

    private static void terminate(Process process) throws InterruptedException {
        List<ProcessHandle> descendants = process.descendants()
                .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .toList();
        requestTermination(process, descendants);
        process.waitFor(2, TimeUnit.SECONDS);
        forceTermination(process, descendants);
        process.waitFor(2, TimeUnit.SECONDS);
    }

    private static void requestTermination(Process process, List<ProcessHandle> descendants) {
        for (ProcessHandle descendant : descendants) {
            descendant.destroy();
        }
        process.destroy();
    }

    private static void forceTermination(Process process, List<ProcessHandle> descendants) {
        for (ProcessHandle descendant : descendants) {
            forceTermination(descendant);
        }
        forceTermination(process.toHandle());
    }

    private static void forceTermination(ProcessHandle process) {
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static Path canonicalDirectory(Path path, String code) throws IOException {
        if (path == null || !path.isAbsolute() || Files.isSymbolicLink(path)
                || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(code);
        }
        Path real = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.equals(path.normalize())) {
            throw new IllegalArgumentException(code);
        }
        return real;
    }

    private static Path canonicalListener(Path path) throws IOException {
        requireListenerReference(path);
        Path real = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        requireCanonicalListener(path, real);
        requireListenerType(real);
        return real;
    }

    private static void requireListenerReference(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("listenerPathInvalid");
        }
        requireAbsoluteListener(path);
        requireUnlinkedListener(path);
        requireExistingListener(path);
    }

    private static void requireAbsoluteListener(Path path) {
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("listenerPathInvalid");
        }
    }

    private static void requireUnlinkedListener(Path path) {
        if (Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("listenerPathInvalid");
        }
    }

    private static void requireExistingListener(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("listenerPathInvalid");
        }
    }

    private static void requireCanonicalListener(Path path, Path real) {
        if (!real.equals(path.normalize())) {
            throw new IllegalArgumentException("listenerPathInvalid");
        }
    }

    private static void requireListenerType(Path real) {
        if (Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("listenerPathInvalid");
        }
    }

    private static void executable(Path path, String code) {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.isExecutable(path)) {
            throw new IllegalArgumentException(code);
        }
    }
}
