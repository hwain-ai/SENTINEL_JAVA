package io.github.hwainhwang.sentinel.cli;

import io.github.hwainhwang.sentinel.crap.CrapGate;
import io.github.hwainhwang.sentinel.crap.GateThreshold;
import io.github.hwainhwang.sentinel.crap.Models;
import io.github.hwainhwang.sentinel.crap.SemanticSite;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Runs a project's production CRAP gate against a fresh JaCoCo XML report.
 *
 * <p>Usage: {@code [--crap-max TEXT] [--only PATH]... ROOT SOURCE_ROOT COVERAGE_XML [CLASSPATH...]}.
 * {@code --only} restricts the judged callables to the named project-relative source paths.
 */
public final class SelfCrapMain {
    private record Invocation(GateThreshold crapMax, Set<String> only, Set<String> functions, String[] positional, boolean listFunctions) {
        static Invocation parse(String[] arguments) {
            List<String> values = new ArrayList<>(Arrays.asList(arguments));
            boolean listFunctions = values.remove("--list-functions");
            arguments = values.toArray(String[]::new);
            GateThreshold crapMax = GateThreshold.DEFAULT_CRAP_MAX;
            Set<String> only = new TreeSet<>();
            Set<String> functions = new TreeSet<>();
            int index = 0;
            while (index + 1 < arguments.length && arguments[index].startsWith("--")) {
                if ("--crap-max".equals(arguments[index])) {
                    crapMax = GateThreshold.crapMax(arguments[index + 1]);
                } else if ("--only".equals(arguments[index])) {
                    only.add(onlyPath(arguments[index + 1]));
                } else if ("--function".equals(arguments[index])) {
                    functions.add(arguments[index + 1]);
                } else {
                    throw new IllegalArgumentException("usage");
                }
                index += 2;
            }
            return new Invocation(
                    crapMax,
                    only.isEmpty() ? null : Set.copyOf(only),
                    Set.copyOf(functions),
                    Arrays.copyOfRange(arguments, index, arguments.length), listFunctions);
        }

        private static String onlyPath(String value) {
            if (value.isEmpty() || value.startsWith("/")) {
                throw new IllegalArgumentException("changedPathInvalid");
            }
            for (String part : value.split("/", -1)) {
                if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                    throw new IllegalArgumentException("changedPathInvalid");
                }
            }
            return value;
        }
    }

    private SelfCrapMain() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] arguments) {
        int exit = run(arguments, System.out, System.err);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    public static int run(String[] arguments, PrintStream out, PrintStream error) {
        if (arguments == null || out == null || error == null) {
            error.println("self-crap error: usage");
            return 4;
        }
        try {
            Invocation invocation = Invocation.parse(arguments);
            String[] positional = invocation.positional();
            if (invocation.listFunctions()) {
                return describeFunctions(invocation, out);
            }
            if (positional.length < 3) {
                error.println("self-crap error: usage");
                return 4;
            }
            Path root = projectRoot(positional[0]);
            Path sourceRoot = child(root, positional[1], true);
            Path coverage = child(root, positional[2], false);
            CrapGate.Result result = CrapGate.evaluate(
                    sources(root, sourceRoot),
                    read(coverage),
                    classpath(root, positional),
                    invocation.crapMax(),
                    invocation.only(), invocation.functions());
            out.println(summary(result, invocation.crapMax()));
            printFailures(result.rows(), error, invocation.crapMax());
            return result.passed() ? 0 : 2;
        } catch (IOException | RuntimeException failure) {
            error.println("self-crap error: " + safeMessage(failure));
            return 4;
        }
    }

    private static Path projectRoot(String value) {
        Path root = Path.of(value).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("projectRootInvalid");
        }
        return root;
    }

    private static Path child(Path root, String value, boolean directory) {
        Path relative = Path.of(value);
        if (relative.isAbsolute() || !relative.normalize().equals(relative)) {
            throw new IllegalArgumentException("projectPathInvalid");
        }
        Path child = root.resolve(relative);
        boolean expectedType = directory
                ? Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                : Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(child) || !expectedType) {
            throw new IllegalArgumentException(directory ? "sourceRootInvalid" : "coverageReportInvalid");
        }
        return child;
    }

    private static Map<String, byte[]> sources(Path root, Path sourceRoot) throws IOException {
        List<Path> files = new ArrayList<>();
        try (var paths = Files.walk(sourceRoot)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (Files.isSymbolicLink(path)) {
                    throw new IllegalArgumentException("sourceSymlinkRejected");
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && path.getFileName().toString().endsWith(".java")) {
                    files.add(path);
                }
            }
        }
        files.sort(SelfCrapMain::comparePaths);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("productionSourceMissing");
        }
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (Path path : files) {
            String relative = root.relativize(path).toString().replace('\\', '/');
            result.put(relative, read(path));
        }
        return Map.copyOf(result);
    }

    private static int comparePaths(Path left, Path right) {
        return SemanticSite.compareUtf8(left.toString(), right.toString());
    }

    private static byte[] read(Path path) throws IOException {
        return Files.readAllBytes(path);
    }

    private static List<Path> classpath(Path root, String[] arguments) {
        return classpath(root, arguments, 3);
    }

    private static int describeFunctions(Invocation invocation, PrintStream out) throws IOException {
        String[] positional = invocation.positional();
        if (positional.length < 2) throw new IllegalArgumentException("usage");
        Path root = projectRoot(positional[0]);
        var definitions = io.github.hwainhwang.sentinel.crap.JavaAnalyzer.analyzeAll(
                sources(root, child(root, positional[1], true)), classpath(root, positional, 2));
        var selected = CrapGate.selectDefinitions(definitions, invocation.only(), invocation.functions());
        var report = Map.of("functions", selected.stream().map(SelfCrapMain::callableReport).toList());
        out.println(new String(io.github.hwainhwang.sentinel.evidence.CanonicalJson.file(report),
                java.nio.charset.StandardCharsets.UTF_8).strip());
        return 0;
    }

    private static List<Path> classpath(Path root, String[] arguments, int start) {
        List<Path> paths = new ArrayList<>();
        for (int index = start; index < arguments.length; index++) {
            Path entry = Path.of(arguments[index]);
            if (!entry.normalize().equals(entry)) {
                throw new IllegalArgumentException("dependencyClasspathInvalid");
            }
            // Absolute entries let a caller name the checker's own locked jars outside the project.
            Path path = entry.isAbsolute() ? entry : root.resolve(entry);
            boolean validType = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
            if (Files.isSymbolicLink(path) || !validType) {
                throw new IllegalArgumentException("dependencyClasspathInvalid");
            }
            paths.add(path);
        }
        return List.copyOf(paths);
    }

    private static String summary(CrapGate.Result result, GateThreshold crapMax) {
        Map<String, Object> report = new java.util.TreeMap<>();
        report.put("schemaVersion", "sentinel-java-self-crap-v1");
        report.put("passed", result.passed());
        report.put("crapMax", crapMax.text());
        report.put("total", result.total());
        report.put("known", result.known());
        report.put("unknown", result.unknown());
        report.put("aboveLimit", result.aboveLimit());
        report.put("functions", result.metrics().stream().map(SelfCrapMain::metricReport).toList());
        return new String(io.github.hwainhwang.sentinel.evidence.CanonicalJson.file(report), java.nio.charset.StandardCharsets.UTF_8).strip();
    }

    private static Map<String, Object> metricReport(Models.CallableMetric metric) {
        Models.CallableDefinition callable = metric.callable();
        Map<String, Object> row = callableReport(callable);
        row.put("complexity", callable.complexity());
        row.put("coveredUnits", metric.coveredUnits());
        row.put("totalUnits", metric.totalUnits());
        row.put("coverageBasis", "jacoco-instruction");
        row.put("score", metric.known() ? metric.crap().decimal() : null);
        row.put("pass", metric.known() && metric.crap().passed());
        row.put("reason", metric.known() ? (metric.crap().passed() ? "passed" : "crapThresholdExceeded") : metric.unknownReason().name());
        return row;
    }

    private static Map<String, Object> callableReport(Models.CallableDefinition callable) {
        Map<String, Object> row = new java.util.TreeMap<>();
        row.put("file", callable.identity().moduleRelativePath());
        row.put("function", callable.identity().owner().replace('/', '.') + "." + callable.identity().callableName());
        row.put("id", callable.identity().callableId());
        row.put("line", callable.declarationLine());
        row.put("endLine", callable.sourceEndLine());
        return row;
    }

    private static void printFailures(
            List<Models.CrapRow> rows, PrintStream error, GateThreshold crapMax) {
        for (Models.CrapRow row : rows) {
            if (!row.known()) {
                error.println("CRAP_UNKNOWN " + row.callableId() + " " + row.unknownReason());
            } else if (!crapMax.crapPasses(row.numerator(), row.denominator())) {
                error.println("CRAP_ABOVE " + row.callableId() + " "
                        + row.numerator() + "/" + row.denominator());
            }
        }
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message;
    }
}
