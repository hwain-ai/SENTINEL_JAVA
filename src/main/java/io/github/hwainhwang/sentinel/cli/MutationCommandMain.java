package io.github.hwainhwang.sentinel.cli;

import io.github.hwainhwang.sentinel.crap.GateThreshold;
import io.github.hwainhwang.sentinel.evidence.CanonicalJson;
import io.github.hwainhwang.sentinel.mutation.MutationRun;
import io.github.hwainhwang.sentinel.mutation.ProjectMutationRequest;
import io.github.hwainhwang.sentinel.mutation.ProjectMutationRunner;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Harness-neutral project mutation entrypoint with explicit paths and no shell command input. */
public final class MutationCommandMain {
    private static final Set<String> OPTIONS = Set.of(
            "--project",
            "--inventory",
            "--backend",
            "--java-home",
            "--maven-home",
            "--maven-repository",
            "--listener",
            "--timeout-millis");
    private static final String MUTATION_MIN = "--mutation-min";
    private static final String CHANGED_FILE = "--changed-file";
    private static final Pattern SAFE_CODE = Pattern.compile("[a-z][A-Za-z0-9]{0,63}");

    private MutationCommandMain() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] arguments) {
        int exit = run(arguments, System.out, System.err);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    public static int run(String[] arguments, PrintStream output, PrintStream error) {
        if (invalidCall(arguments, output, error)) {
            return usage(error);
        }
        try {
            MutationRun run = new ProjectMutationRunner().run(request(arguments));
            return write(run, output);
        } catch (Exception failure) {
            return failure(error, failure);
        }
    }

    static int run(
            String[] arguments,
            PrintStream output,
            PrintStream error,
            Function<ProjectMutationRequest, MutationRun> executor) {
        if (invalidCall(arguments, output, error) || executor == null) {
            return usage(error);
        }
        try {
            ProjectMutationRequest request = request(arguments);
            MutationRun run = executor.apply(request);
            return write(run, output);
        } catch (Exception failure) {
            return failure(error, failure);
        }
    }

    private static boolean invalidCall(String[] arguments, PrintStream output, PrintStream error) {
        int required = OPTIONS.size() * 2;
        return arguments == null || arguments.length < required || arguments.length % 2 != 0
                || output == null || error == null;
    }

    private static int write(MutationRun run, PrintStream output) {
        byte[] payload = CanonicalJson.file(run.evidenceComponent());
        output.write(payload, 0, payload.length);
        if (output.checkError()) {
            throw new IllegalStateException("mutationOutputWriteFailed");
        }
        return Boolean.TRUE.equals(run.evidenceComponent().get("pass")) ? 0 : 2;
    }

    private static int failure(PrintStream error, Exception failure) {
        error.println("mutation error: " + safeCode(failure));
        return 4;
    }

    private static ProjectMutationRequest request(String[] arguments) {
        List<String> changed = new ArrayList<>();
        Map<String, String> values = pairs(arguments, changed);
        String mutationMin = values.remove(MUTATION_MIN);
        if (!values.keySet().equals(OPTIONS)) {
            throw new IllegalArgumentException("usage");
        }
        return new ProjectMutationRequest(
                absolute(values.get("--project")),
                absolute(values.get("--inventory")),
                absolute(values.get("--backend")),
                absolute(values.get("--java-home")),
                absolute(values.get("--maven-home")),
                absolute(values.get("--maven-repository")),
                absolute(values.get("--listener")),
                timeout(values.get("--timeout-millis")),
                mutationMin == null
                        ? GateThreshold.DEFAULT_MUTATION_MIN
                        : GateThreshold.mutationMin(mutationMin),
                changed.isEmpty() ? null : Set.copyOf(changed));
    }

    private static Map<String, String> pairs(String[] arguments, List<String> changed) {
        Map<String, String> values = new HashMap<>();
        for (int index = 0; index < arguments.length; index += 2) {
            String option = arguments[index];
            String value = arguments[index + 1];
            if (CHANGED_FILE.equals(option)) {
                changed.add(changedPath(value));
            } else {
                putOption(values, option, value);
            }
        }
        return values;
    }

    private static void putOption(Map<String, String> values, String option, String value) {
        boolean known = OPTIONS.contains(option) || MUTATION_MIN.equals(option);
        if (!known || value.isEmpty() || values.put(option, value) != null) {
            throw new IllegalArgumentException("usage");
        }
    }

    private static String changedPath(String value) {
        if (value.isEmpty() || value.startsWith("/")) {
            throw new IllegalArgumentException("changedPathInvalid");
        }
        for (String part : value.split("/", -1)) {
            if (!plainSegment(part)) {
                throw new IllegalArgumentException("changedPathInvalid");
            }
        }
        return value;
    }

    private static boolean plainSegment(String part) {
        return !part.isEmpty() && !".".equals(part) && !"..".equals(part);
    }

    private static Path absolute(String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute() || !path.normalize().equals(path)) {
            throw new IllegalArgumentException("usage");
        }
        return path;
    }

    private static long timeout(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("usage");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("usage", failure);
        }
    }

    private static int usage(PrintStream error) {
        error.println("mutation error: usage");
        return 4;
    }

    private static String safeCode(Exception failure) {
        String message = failure.getMessage();
        if (message == null) {
            return failure.getClass().getSimpleName();
        }
        return safeMessage(message, failure);
    }

    private static String safeMessage(String message, Exception failure) {
        return SAFE_CODE.matcher(message).matches()
                ? message : failure.getClass().getSimpleName();
    }
}
