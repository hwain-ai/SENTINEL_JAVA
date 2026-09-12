package io.github.hwainhwang.sentinel.cli;

import io.github.hwainhwang.sentinel.evidence.CanonicalJson;
import io.github.hwainhwang.sentinel.mutation.PitProbe;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Separate opt-in observation command. No successful execution is a certified quality pass. */
public final class PitProbeMain {
    private static final Map<String, Integer> EXITS = Map.of(
            "pitProbeUsage", 3, "pitProbeRequestInvalid", 3, "pitProbeArtifactsInvalid", 5,
            "pitProbeBaselineFailed", 4, "pitProbeCompileFailed", 4,
            "pitProbeCancelled", 8, "pitProbeTimedOut", 7);

    private PitProbeMain() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] arguments) {
        int exit = run(arguments, System.out, System.err);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    public static int run(String[] arguments, PrintStream out, PrintStream error) {
        try {
            if (help(arguments)) {
                out.println("PIT probe: --project PATH --artifacts PATH --target-class CLASS --test-class CLASS [--timeout-ms N]");
                return 0;
            }
            out.writeBytes(CanonicalJson.file(PitProbe.run(parse(arguments))));
            error.println("pit probe: backendNotAdmitted");
            return 6;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return failure("pitProbeCancelled", error);
        } catch (IOException failure) {
            return failure("pitProbeIoFailed", error);
        } catch (RuntimeException failure) {
            return failure(safeCode(failure), error);
        }
    }

    private static boolean help(String[] arguments) {
        return arguments != null && arguments.length == 1 && "--help".equals(arguments[0]);
    }

    private static int failure(String code, PrintStream error) {
        error.println("pit probe: " + code);
        return EXITS.getOrDefault(code, 6);
    }

    private static String safeCode(RuntimeException error) {
        String message = error.getMessage();
        if (error instanceof IllegalArgumentException && message != null && message.matches("pit[A-Z][A-Za-z]+")) {
            return message;
        }
        return "pitProbeInternalFailure";
    }

    private static PitProbe.Request parse(String[] arguments) {
        if (arguments == null || arguments.length == 0 || arguments.length % 2 != 0 || arguments.length > 520) {
            throw usage();
        }
        Options options = new Options();
        for (int index = 0; index < arguments.length; index += 2) {
            options.add(arguments[index], arguments[index + 1]);
        }
        return options.request();
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException("pitProbeUsage");
    }

    private static final class Options {
        private final Map<String, String> singles = new LinkedHashMap<>();
        private final List<String> targets = new ArrayList<>();
        private final List<String> tests = new ArrayList<>();

        void add(String option, String value) {
            if (option == null || value == null) {
                throw usage();
            }
            switch (option) {
                case "--target-class" -> targets.add(value);
                case "--test-class" -> tests.add(value);
                case "--project", "--artifacts", "--timeout-ms" -> single(option, value);
                default -> throw usage();
            }
        }

        private void single(String option, String value) {
            if (singles.put(option, value) != null) {
                throw usage();
            }
        }

        PitProbe.Request request() {
            if (!singles.containsKey("--project") || !singles.containsKey("--artifacts")) {
                throw usage();
            }
            try {
                return new PitProbe.Request(Path.of(singles.get("--project")).toAbsolutePath().normalize(),
                        Path.of(singles.get("--artifacts")).toAbsolutePath().normalize(), targets, tests,
                        Long.parseLong(singles.getOrDefault("--timeout-ms", "30000")));
            } catch (IllegalArgumentException failure) {
                throw usage();
            }
        }
    }
}
