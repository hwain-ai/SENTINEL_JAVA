package io.github.hwainhwang.sentinel.cli;

import io.github.hwainhwang.sentinel.mutation.MutationNormalizer;
import io.github.hwainhwang.sentinel.mutation.MutationProof;
import io.github.hwainhwang.sentinel.mutation.MutationState;
import io.github.hwainhwang.sentinel.mutation.RawMutationStatus;
import io.github.hwainhwang.sentinel.mutation.TestExecution;
import io.github.hwainhwang.sentinel.mutation.junit.JUnitEventSummary;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Proves one raw killed candidate using two controls and two typed mutant replays. */
public final class MutationProofMain {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private MutationProofMain() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] arguments) {
        int exit = run(arguments, System.out, System.err);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    public static int run(String[] arguments, PrintStream out, PrintStream error) {
        if (arguments == null || arguments.length != 9 || out == null || error == null) {
            return usage(error);
        }
        try {
            String original = arguments[0];
            if (!SHA256.matcher(original).matches()) {
                throw new IllegalArgumentException("originalSourceDigestInvalid");
            }
            List<JUnitEventSummary> summaries = read(arguments);
            MutationProof proof = proof(original, summaries);
            MutationState state = MutationNormalizer.normalize(RawMutationStatus.KILLED, proof);
            out.println(result(state));
            return state == MutationState.KILLED ? 0 : 2;
        } catch (IOException | RuntimeException failure) {
            error.println("mutation proof error: " + safeMessage(failure));
            return 4;
        }
    }

    private static int usage(PrintStream error) {
        error.println("mutation proof error: usage");
        return 4;
    }

    private static List<JUnitEventSummary> read(String[] arguments) throws IOException {
        List<JUnitEventSummary> summaries = new ArrayList<>();
        Set<String> nonces = new HashSet<>();
        for (int index = 1; index < arguments.length; index += 2) {
            JUnitEventSummary summary = JUnitEventSummary.read(
                    Path.of(arguments[index]), arguments[index + 1]);
            if (!nonces.add(summary.execution().nonce())) {
                throw new IllegalArgumentException("executionNonceDuplicate");
            }
            summaries.add(summary);
        }
        return summaries;
    }

    private static MutationProof proof(String original, List<JUnitEventSummary> summaries) {
        List<TestExecution> controls = new ArrayList<>();
        List<TestExecution> mutants = new ArrayList<>();
        Set<String> mutantDigests = new HashSet<>();
        for (JUnitEventSummary summary : summaries) {
            if (summary.sourceSha256().equals(original)) {
                controls.add(summary.execution());
            } else {
                mutants.add(summary.execution());
                mutantDigests.add(summary.sourceSha256());
            }
        }
        if (controls.size() != 2 || mutants.size() != 2 || mutantDigests.size() != 1) {
            throw new IllegalArgumentException("mutationProofCardinalityInvalid");
        }
        controls.sort(MutationProofMain::compareExecution);
        mutants.sort(MutationProofMain::compareExecution);
        return new MutationProof(controls.get(0), controls.get(1), mutants.get(0), mutants.get(1));
    }

    private static int compareExecution(TestExecution left, TestExecution right) {
        return left.nonce().compareTo(right.nonce());
    }

    private static String result(MutationState state) {
        String rate = state == MutationState.KILLED ? "100.000000" : "0.000000";
        return "{\"schemaVersion\":\"sentinel-java-mutation-proof-v1\","
                + "\"state\":\"" + state.name() + "\","
                + "\"candidateCount\":1,\"controls\":2,\"replays\":2,"
                + "\"strictKillRate\":\"" + rate + "\"}";
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message;
    }
}
