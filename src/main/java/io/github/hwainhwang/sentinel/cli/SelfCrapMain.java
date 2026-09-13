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

/**
 * Runs a project's production CRAP gate against a fresh JaCoCo XML report.
 *
 * <p>Usage: {@code [--crap-max TEXT] ROOT SOURCE_ROOT COVERAGE_XML [CLASSPATH...]}.
 */
public final class SelfCrapMain {
    private record Invocation(GateThreshold crapMax, String[] positional) {
        static Invocation parse(String[] arguments) {
            if (arguments.length >= 2 && "--crap-max".equals(arguments[0])) {
                return new Invocation(
                        GateThreshold.crapMax(arguments[1]),
                        Arrays.copyOfRange(arguments, 2, arguments.length));
            }
            return new Invocation(GateThreshold.DEFAULT_CRAP_MAX, arguments);
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
                    invocation.crapMax());
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
        List<Path> paths = new ArrayList<>();
        for (int index = 3; index < arguments.length; index++) {
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
        return "{\"schemaVersion\":\"sentinel-java-self-crap-v1\",\"passed\":"
                + result.passed()
                + ",\"crapMax\":\"" + crapMax.text() + "\""
                + ",\"total\":" + result.total()
                + ",\"known\":" + result.known()
                + ",\"unknown\":" + result.unknown()
                + ",\"aboveLimit\":" + result.aboveLimit()
                + "}";
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
