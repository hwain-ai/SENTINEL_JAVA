package io.github.hwainhwang.sentinel.cli;

import io.github.hwainhwang.sentinel.mutation.junit.JUnitEventSummary;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

/** Validates that a typed JUnit event belongs to the requested fresh execution. */
public final class JUnitEventValidateMain {
    private JUnitEventValidateMain() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] arguments) {
        int exit = run(arguments, System.err);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    public static int run(String[] arguments, PrintStream error) {
        if (arguments == null || arguments.length != 4 || error == null) {
            return failure(error, "usage");
        }
        try {
            JUnitEventSummary summary = JUnitEventSummary.read(
                    Path.of(arguments[0]), arguments[3]);
            if (!summary.execution().nonce().equals(arguments[1])
                    || !summary.sourceSha256().equals(arguments[2])) {
                throw new IllegalArgumentException("junitEventIdentityMismatch");
            }
            return 0;
        } catch (IOException | RuntimeException failure) {
            return failure(error, failure.getMessage());
        }
    }

    private static int failure(PrintStream error, String message) {
        if (error != null) {
            error.println("junit event error: " + message);
        }
        return 4;
    }
}
