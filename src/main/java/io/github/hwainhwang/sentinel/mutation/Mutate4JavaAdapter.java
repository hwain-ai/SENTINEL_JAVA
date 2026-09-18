package io.github.hwainhwang.sentinel.mutation;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Uses the pinned backend's public model and callbacks without parsing console text. */
final class Mutate4JavaAdapter implements AutoCloseable {
    private static final String PINNED_SHA256 =
            "117ef17d0dfe32e50f0449a652b83c92a377858cdcdd9c1d191a8d4a69d70ad3";
    private static final String TEST_COMMAND = "sentinel-typed-v1";

    private final URLClassLoader loader;
    private final Class<?> executorType;
    private final Class<?> progressReporterType;
    private final Constructor<?> testRunConstructor;
    private final Constructor<?> mutationCatalogConstructor;
    private final Constructor<?> processCommandExecutorConstructor;
    private final Constructor<?> coverageRunnerConstructor;
    private final Constructor<?> workspaceManagerConstructor;
    private final Constructor<?> cliApplicationConstructor;
    private final Method catalogAnalyze;
    private final Method analysisSites;
    private final Method siteFile;
    private final Method siteLine;
    private final Method siteStart;
    private final Method siteEnd;
    private final Method siteOriginalText;
    private final Method siteReplacementText;
    private final Method siteDescription;
    private final Method siteScopeId;
    private final Method siteScopeKind;
    private final Method siteScopeStartLine;
    private final Method siteScopeEndLine;
    private final Method cliExecute;
    private final Method testRunPassed;
    private final Method testRunTimedOut;
    private final Method jobSite;
    private final Method jobOrder;
    private final Method jobTotalJobs;
    private final Method resultSite;
    private final Method resultKilled;
    private final Method resultTimedOut;
    private final Method resultOrder;
    private final Method resultTotalJobs;

    Mutate4JavaAdapter(Path backendJar) throws Exception {
        Path jar = pinnedJar(backendJar);
        this.loader = new URLClassLoader(
                new URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        this.executorType = type("mutate4java.TestCommandExecutor");
        this.progressReporterType = type("mutate4java.report.ProgressReporter");
        Class<?> testRunType = type("mutate4java.model.TestRun");
        Class<?> catalogType = type("mutate4java.analysis.MutationCatalog");
        Class<?> analysisType = type("mutate4java.model.SourceAnalysis");
        Class<?> siteType = type("mutate4java.model.MutationSite");
        Class<?> processExecutorType = type("mutate4java.exec.ProcessCommandExecutor");
        Class<?> coverageRunnerType = type("mutate4java.coverage.CoverageRunner");
        Class<?> workspaceManagerType = type("mutate4java.exec.WorkspaceManager");
        Class<?> copiedWorkspaceManagerType = type("mutate4java.exec.CopiedWorkspaceManager");
        Class<?> cliType = type("mutate4java.cli.CliApplication");
        Class<?> jobType = type("mutate4java.model.MutationJob");
        Class<?> resultType = type("mutate4java.model.MutationResult");
        this.testRunConstructor = testRunType.getConstructor(
                int.class, String.class, long.class, boolean.class);
        this.mutationCatalogConstructor = catalogType.getConstructor();
        this.processCommandExecutorConstructor = processExecutorType.getConstructor();
        this.coverageRunnerConstructor = coverageRunnerType.getConstructor(processExecutorType);
        this.workspaceManagerConstructor = copiedWorkspaceManagerType.getConstructor();
        this.cliApplicationConstructor = cliType.getConstructor(
                Path.class,
                PrintStream.class,
                PrintStream.class,
                executorType,
                coverageRunnerType,
                workspaceManagerType,
                progressReporterType);
        this.catalogAnalyze = catalogType.getMethod("analyze", Path.class);
        this.analysisSites = analysisType.getMethod("sites");
        this.siteFile = siteType.getMethod("file");
        this.siteLine = siteType.getMethod("lineNumber");
        this.siteStart = siteType.getMethod("start");
        this.siteEnd = siteType.getMethod("end");
        this.siteOriginalText = siteType.getMethod("originalText");
        this.siteReplacementText = siteType.getMethod("replacementText");
        this.siteDescription = siteType.getMethod("description");
        this.siteScopeId = siteType.getMethod("scopeId");
        this.siteScopeKind = siteType.getMethod("scopeKind");
        this.siteScopeStartLine = siteType.getMethod("scopeStartLine");
        this.siteScopeEndLine = siteType.getMethod("scopeEndLine");
        this.cliExecute = cliType.getMethod("execute", String[].class);
        this.testRunPassed = testRunType.getMethod("passed");
        this.testRunTimedOut = testRunType.getMethod("timedOut");
        this.jobSite = jobType.getMethod("site");
        this.jobOrder = jobType.getMethod("order");
        this.jobTotalJobs = jobType.getMethod("totalJobs");
        this.resultSite = resultType.getMethod("site");
        this.resultKilled = resultType.getMethod("killed");
        this.resultTimedOut = resultType.getMethod("timedOut");
        this.resultOrder = resultType.getMethod("order");
        this.resultTotalJobs = resultType.getMethod("totalJobs");
    }

    List<MutationCandidate> scan(Path snapshotRoot, ProductionSource source) throws Exception {
        Path sourcePath = snapshotRoot.resolve(source.relativePath()).normalize();
        Object catalog = mutationCatalogConstructor.newInstance();
        Object analysis = call(catalogAnalyze, catalog, sourcePath);
        List<?> sites = requireList(call(analysisSites, analysis));
        List<MutationCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < sites.size(); index++) {
            candidates.add(candidate(sourcePath, source, sites.get(index), index + 1));
        }
        return List.copyOf(candidates);
    }

    Replay replay(
            Path snapshotRoot,
            List<ProductionSource> sources,
            ProductionSource source,
            List<MutationCandidate> expected,
            TestExecution control,
            TypedMavenRunner runner) throws Exception {
        requireReplayInput(expected, control);
        MavenModule module = MavenModule.locate(snapshotRoot, source.relativePath());
        List<ProductionSource> moduleSources = moduleSources(
                snapshotRoot, module.root(), sources);
        TypedReplayCollector collector = new TypedReplayCollector(
                expected, snapshotRoot, module.root());
        Object executor = executor(
                source, module.relativeSource(), moduleSources, runner, collector);
        Object reporter = reporter(collector);
        String before = ProductionInventory.sha256(snapshotRoot.resolve(source.relativePath()));
        int exitCode = backendExecution(snapshotRoot, executor, reporter, source.relativePath(), expected);
        String after = ProductionInventory.sha256(snapshotRoot.resolve(source.relativePath()));
        requireUnchangedSource(before, after, source.sha256());
        collector.complete(exitCode);
        return new Replay(expected, collector.executions());
    }

    private MutationCandidate candidate(
            Path sourcePath, ProductionSource source, Object site, int ordinal) throws Exception {
        requireSiteFile(site, sourcePath);
        int line = (Integer) call(siteLine, site);
        String description = (String) call(siteDescription, site);
        String id = candidateId(source.relativePath(), source.sha256(), site, ordinal);
        return new MutationCandidate(
                id,
                source.relativePath(),
                source.sha256(),
                line,
                description,
                ordinal);
    }

    private String candidateId(
            String relativePath, String sourceSha256, Object site, int ordinal)
            throws Exception {
        return MutationHash.digest(
                "sentinel-java-mutation-candidate-v2",
                List.of(
                        relativePath,
                        sourceSha256,
                        accessorText(siteLine, site),
                        accessorText(siteStart, site),
                        accessorText(siteEnd, site),
                        accessorText(siteOriginalText, site),
                        accessorText(siteReplacementText, site),
                        accessorText(siteDescription, site),
                        accessorText(siteScopeId, site),
                        accessorText(siteScopeKind, site),
                        accessorText(siteScopeStartLine, site),
                        accessorText(siteScopeEndLine, site),
                        Integer.toString(ordinal)));
    }

    private static String accessorText(Method accessor, Object target) throws Exception {
        Object value = call(accessor, target);
        if (value == null) {
            throw new IllegalStateException("mutationBackendApiUnexpected");
        }
        return value.toString();
    }

    private void requireSiteFile(Object site, Path expected) throws Exception {
        Object value = call(siteFile, site);
        if (!(value instanceof Path actual) || !actual.normalize().equals(expected)) {
            throw new IllegalStateException("mutationCandidateInventoryInvalid");
        }
    }

    private Object executor(
            ProductionSource source,
            String moduleRelativeSource,
            List<ProductionSource> moduleSources,
            TypedMavenRunner runner,
            TypedReplayCollector collector) {
        InvocationHandler handler = new TypedExecutorHandler(
                source, moduleRelativeSource, moduleSources, runner, collector);
        return Proxy.newProxyInstance(loader, new Class<?>[]{executorType}, handler);
    }

    private Object reporter(TypedReplayCollector collector) {
        InvocationHandler handler = new TypedReporterHandler(collector);
        return Proxy.newProxyInstance(
                loader, new Class<?>[]{progressReporterType}, handler);
    }

    private int backendExecution(
            Path root, Object executor, Object reporter, String relativeSource, List<MutationCandidate> expected)
            throws Exception {
        Object processExecutor = processCommandExecutorConstructor.newInstance();
        Object coverageRunner = coverageRunnerConstructor.newInstance(processExecutor);
        Object workspaceManager = workspaceManagerConstructor.newInstance();
        try (PrintStream output = new PrintStream(OutputStream.nullOutputStream())) {
            Object application = cliApplicationConstructor.newInstance(
                    root, output, output, executor, coverageRunner, workspaceManager, reporter);
            Object result = call(
                    cliExecute,
                    application,
                    (Object) new String[]{
                        relativeSource,
                        "--lines", expected.stream().map(candidate -> Integer.toString(candidate.line())).distinct().collect(java.util.stream.Collectors.joining(",")),
                        "--max-workers", "1",
                        "--test-command", TEST_COMMAND,
                        "--verbose"
                    });
            return (Integer) result;
        }
    }

    private Object backendPass(long durationMillis) throws Exception {
        return testRunConstructor.newInstance(0, "", durationMillis, false);
    }

    private static void requireReplayInput(
            List<MutationCandidate> expected, TestExecution control) {
        if (expected.isEmpty()) {
            throw new IllegalArgumentException("mutationCandidateInventoryInvalid");
        }
        if (control.status() != ExecutionStatus.PASSED) {
            throw new IllegalArgumentException("mutationControlInvalid");
        }
    }

    private static void requireUnchangedSource(
            String before, String after, String expected) {
        if (!before.equals(expected) || !after.equals(expected)) {
            throw new IllegalStateException("snapshotSourceIdentityChanged");
        }
    }

    private Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, true, loader);
    }

    private static List<?> requireList(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("mutationBackendApiUnexpected");
        }
        return list;
    }

    private static Object call(Method method, Object target, Object... arguments)
            throws Exception {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException("mutationBackendInvocationFailed", cause);
        }
    }

    private static Object objectMethod(
            Object proxy, Method method, Object[] arguments, String label) {
        if (method.getName().equals("equals")) {
            return proxy == arguments[0];
        }
        return nonEqualityObjectMethod(proxy, method, label);
    }

    private static Object nonEqualityObjectMethod(
            Object proxy, Method method, String label) {
        if (method.getName().equals("hashCode")) {
            return System.identityHashCode(proxy);
        }
        return requireToStringMethod(method, label);
    }

    private static String requireToStringMethod(Method method, String label) {
        if (method.getName().equals("toString")) {
            return label;
        }
        throw new IllegalStateException("mutationBackendObjectMethodUnexpected");
    }

    private static Path pinnedJar(Path value) throws IOException {
        if (value == null || !value.isAbsolute() || Files.isSymbolicLink(value)
                || !Files.isRegularFile(value, LinkOption.NOFOLLOW_LINKS)
                || !value.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(value.normalize())
                || !ProductionInventory.sha256(value).equals(PINNED_SHA256)) {
            throw new IllegalArgumentException("mutationBackendIdentityInvalid");
        }
        return value;
    }

    private static List<ProductionSource> moduleSources(
            Path projectRoot, Path moduleRoot, List<ProductionSource> sources) {
        List<ProductionSource> result = new ArrayList<>();
        for (ProductionSource source : sources) {
            Path absolute = projectRoot.resolve(source.relativePath()).normalize();
            if (absolute.startsWith(moduleRoot)) {
                String relative = moduleRoot.relativize(absolute).toString().replace('\\', '/');
                result.add(new ProductionSource(relative, source.sha256()));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public void close() throws IOException {
        loader.close();
    }

    record Replay(List<MutationCandidate> candidates, List<TestExecution> executions) {}

    private final class TypedExecutorHandler implements InvocationHandler {
        private final ProductionSource source;
        private final String moduleRelativeSource;
        private final List<ProductionSource> moduleSources;
        private final TypedMavenRunner runner;
        private final TypedReplayCollector collector;

        private TypedExecutorHandler(
                ProductionSource source,
                String moduleRelativeSource,
                List<ProductionSource> moduleSources,
                TypedMavenRunner runner,
                TypedReplayCollector collector) {
            this.source = source;
            this.moduleRelativeSource = moduleRelativeSource;
            this.moduleSources = moduleSources;
            this.runner = runner;
            this.collector = collector;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, arguments, "sentinelTypedMutationExecutor");
            }
            if (method.getName().equals("withCommand")) {
                collector.command(requireCommand(arguments));
                return proxy;
            }
            if (!method.getName().equals("runTests")) {
                throw new IllegalStateException("mutationBackendApiUnexpected");
            }
            return runTests(arguments);
        }

        private Object runTests(Object[] arguments) throws Exception {
            requireRunArguments(arguments);
            Path root = (Path) arguments[0];
            long timeout = (Long) arguments[1];
            String digest = ProductionInventory.sha256(root.resolve(moduleRelativeSource));
            if (digest.equals(source.sha256())) {
                verifyWorkerSources(root, false);
                collector.baselineExecution();
                return backendPass(runner.backendBaselineDurationMillis());
            }
            return runMutant(root, timeout);
        }

        private Object runMutant(Path root, long timeout) throws Exception {
            long started = System.nanoTime();
            verifyWorkerSources(root, true);
            TestExecution execution;
            try {
                execution = runner.run(root, moduleRelativeSource, timeout);
            } finally {
                verifyWorkerSources(root, true);
            }
            collector.execution(execution);
            long duration = Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
            return backendPass(duration);
        }

        private void verifyWorkerSources(Path root, boolean targetMustDiffer)
                throws IOException {
            Set<String> expected = new HashSet<>();
            for (ProductionSource expectedSource : moduleSources) {
                expected.add(expectedSource.relativePath());
                if (!validWorkerSource(root, expectedSource, targetMustDiffer)) {
                    throw new IllegalStateException("mutationWorkerSourceIdentityChanged");
                }
            }
            if (!ProductionInventory.discoverProduction(root).equals(expected)) {
                throw new IllegalStateException("mutationWorkerSourceIdentityChanged");
            }
        }

        private boolean validWorkerSource(
                Path root, ProductionSource expectedSource, boolean targetMustDiffer)
                throws IOException {
            Path path = root.resolve(expectedSource.relativePath());
            if (Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            Number links = (Number) Files.getAttribute(
                    path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            String actual = ProductionInventory.sha256(path);
            boolean target = expectedSource.relativePath().equals(moduleRelativeSource);
            return links.longValue() == 1L
                    && validDigest(actual, expectedSource.sha256(), target, targetMustDiffer);
        }

        private boolean validDigest(
                String actual, String expected, boolean target, boolean targetMustDiffer) {
            if (target && targetMustDiffer) {
                return !actual.equals(expected);
            }
            return actual.equals(expected);
        }

        private void requireRunArguments(Object[] arguments) {
            if (arguments == null || arguments.length != 2
                    || !(arguments[0] instanceof Path)
                    || !(arguments[1] instanceof Long)) {
                throw new IllegalStateException("mutationBackendApiUnexpected");
            }
        }

        private String requireCommand(Object[] arguments) {
            if (arguments == null || arguments.length != 1
                    || !(arguments[0] instanceof String command)
                    || !command.equals(TEST_COMMAND)) {
                throw new IllegalStateException("mutationBackendCommandUnexpected");
            }
            return command;
        }
    }

    private final class TypedReporterHandler implements InvocationHandler {
        private final TypedReplayCollector collector;

        private TypedReporterHandler(TypedReplayCollector collector) {
            this.collector = collector;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Exception {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, arguments, "sentinelTypedProgressReporter");
            }
            return report(method.getName(), arguments);
        }

        private Object report(String name, Object[] arguments) throws Exception {
            if (name.equals("baselineStarting")) {
                collector.baselineStarting(requirePath(arguments));
                return null;
            }
            if (name.equals("baselineFinished")) {
                collector.baselineFinished(requireOne(arguments));
                return null;
            }
            return reportMutation(name, arguments);
        }

        private Object reportMutation(String name, Object[] arguments) throws Exception {
            if (name.equals("runStarting")) {
                collector.runStarting(requireInt(arguments, 0), requireInt(arguments, 1));
                return null;
            }
            if (name.equals("mutationStarting")) {
                collector.mutationStarting(requireInt(arguments, 0), require(arguments, 1));
                return null;
            }
            if (name.equals("mutationFinished")) {
                collector.mutationFinished(requireInt(arguments, 0), require(arguments, 1));
                return null;
            }
            throw new IllegalStateException("mutationBackendReporterApiUnexpected");
        }

        private Path requirePath(Object[] arguments) {
            Object value = requireOne(arguments);
            if (!(value instanceof Path path)) {
                throw new IllegalStateException("mutationBackendReporterApiUnexpected");
            }
            return path;
        }

        private Object requireOne(Object[] arguments) {
            if (arguments == null || arguments.length != 1) {
                throw new IllegalStateException("mutationBackendReporterApiUnexpected");
            }
            return arguments[0];
        }

        private int requireInt(Object[] arguments, int index) {
            Object value = require(arguments, index);
            if (!(value instanceof Integer number)) {
                throw new IllegalStateException("mutationBackendReporterApiUnexpected");
            }
            return number;
        }

        private Object require(Object[] arguments, int index) {
            if (arguments == null || arguments.length != 2 || arguments[index] == null) {
                throw new IllegalStateException("mutationBackendReporterApiUnexpected");
            }
            return arguments[index];
        }
    }

    private final class TypedReplayCollector {
        private final List<MutationCandidate> expected;
        private final Path snapshotRoot;
        private final Path moduleRoot;
        private final List<TestExecution> executions;
        private int commandCount;
        private int baselineStartingCount;
        private int baselineExecutionCount;
        private int baselineFinishedCount;
        private int runStartingCount;
        private int nextIndex;
        private boolean candidateActive;
        private boolean executionActive;

        private TypedReplayCollector(
                List<MutationCandidate> expected, Path snapshotRoot, Path moduleRoot) {
            this.expected = List.copyOf(expected);
            this.snapshotRoot = snapshotRoot;
            this.moduleRoot = moduleRoot;
            this.executions = new ArrayList<>();
        }

        private synchronized void command(String command) {
            if (!command.equals(TEST_COMMAND) || ++commandCount != 1) {
                throw new IllegalStateException("mutationBackendCommandUnexpected");
            }
        }

        private synchronized void baselineStarting(Path actualModuleRoot) {
            if (!actualModuleRoot.normalize().equals(moduleRoot) || ++baselineStartingCount != 1) {
                throw new IllegalStateException("mutationBackendBaselineInvalid");
            }
        }

        private synchronized void baselineExecution() {
            if (baselineStartingCount != 1 || ++baselineExecutionCount != 1) {
                throw new IllegalStateException("mutationBackendBaselineInvalid");
            }
        }

        private synchronized void baselineFinished(Object testRun) throws Exception {
            boolean passed = (Boolean) call(testRunPassed, testRun);
            boolean timedOut = (Boolean) call(testRunTimedOut, testRun);
            if (baselineExecutionCount != 1 || !passed || timedOut
                    || ++baselineFinishedCount != 1) {
                throw new IllegalStateException("mutationBackendBaselineInvalid");
            }
        }

        private synchronized void runStarting(int totalMutations, int workerCount) {
            if (baselineFinishedCount != 1 || totalMutations != expected.size()
                    || workerCount != 1 || ++runStartingCount != 1) {
                throw new IllegalStateException("mutationResultSetMismatch");
            }
        }

        private synchronized void mutationStarting(int worker, Object job) throws Exception {
            requireReadyCandidate(worker);
            requireJob(job, expected.get(nextIndex), nextIndex);
            candidateActive = true;
            executionActive = false;
        }

        private synchronized void execution(TestExecution execution) {
            if (!candidateActive || executionActive || execution == null) {
                throw new IllegalStateException("mutationExecutionSetMismatch");
            }
            executions.add(execution);
            executionActive = true;
        }

        private synchronized void mutationFinished(int worker, Object result) throws Exception {
            if (worker != 1 || !candidateActive || !executionActive) {
                throw new IllegalStateException("mutationResultSetMismatch");
            }
            requireResult(result, expected.get(nextIndex), nextIndex);
            candidateActive = false;
            executionActive = false;
            nextIndex++;
        }

        private synchronized void requireReadyCandidate(int worker) {
            if (worker != 1 || runStartingCount != 1 || candidateActive
                    || nextIndex >= expected.size()) {
                throw new IllegalStateException("mutationResultSetMismatch");
            }
        }

        private void requireJob(Object job, MutationCandidate candidate, int index)
                throws Exception {
            requireBackendPosition(
                    (Integer) call(jobOrder, job),
                    (Integer) call(jobTotalJobs, job),
                    index);
            requireBackendSite(call(jobSite, job), candidate);
        }

        private void requireResult(Object result, MutationCandidate candidate, int index)
                throws Exception {
            requireBackendPosition(
                    (Integer) call(resultOrder, result),
                    (Integer) call(resultTotalJobs, result),
                    index);
            boolean killed = (Boolean) call(resultKilled, result);
            boolean timedOut = (Boolean) call(resultTimedOut, result);
            if (killed || timedOut) {
                throw new IllegalStateException("mutationResultSetMismatch");
            }
            requireBackendSite(call(resultSite, result), candidate);
        }

        private void requireBackendPosition(int order, int total, int index) {
            if (order != index || total != expected.size()) {
                throw new IllegalStateException("mutationResultSetMismatch");
            }
        }

        private void requireBackendSite(Object site, MutationCandidate candidate)
                throws Exception {
            Path expectedFile = snapshotRoot.resolve(candidate.relativePath()).normalize();
            requireSiteFile(site, expectedFile);
            String actualId = candidateId(
                    candidate.relativePath(),
                    candidate.sourceSha256(),
                    site,
                    candidate.ordinal());
            if (!actualId.equals(candidate.id())) {
                throw new IllegalStateException("mutationResultSetMismatch");
            }
        }

        private synchronized void complete(int exitCode) {
            requireBackendCompletion(exitCode);
            requireBaselineCompletion();
            requireCandidateCompletion();
        }

        private void requireBackendCompletion(int exitCode) {
            if (exitCode != 3 || commandCount != 1 || runStartingCount != 1) {
                throw new IllegalStateException("mutationBackendReplayFailed");
            }
        }

        private void requireBaselineCompletion() {
            if (baselineStartingCount != 1 || baselineExecutionCount != 1
                    || baselineFinishedCount != 1) {
                throw new IllegalStateException("mutationBackendReplayFailed");
            }
        }

        private void requireCandidateCompletion() {
            if (candidateActive || executionActive || nextIndex != expected.size()
                    || executions.size() != expected.size()) {
                throw new IllegalStateException("mutationBackendReplayFailed");
            }
        }

        private synchronized List<TestExecution> executions() {
            return List.copyOf(executions);
        }
    }
}
