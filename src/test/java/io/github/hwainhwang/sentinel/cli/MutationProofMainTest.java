package io.github.hwainhwang.sentinel.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hwainhwang.sentinel.mutation.ExecutionStatus;
import io.github.hwainhwang.sentinel.mutation.TestExecution;
import io.github.hwainhwang.sentinel.mutation.junit.JUnitEventSummary;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MutationProofMainTest {
    private static final String ORIGINAL = "a".repeat(64);
    private static final String MUTANT = "b".repeat(64);
    private static final String INVENTORY = "c".repeat(64);
    private static final String TEST_ID = "d".repeat(64);
    private static final String SIGNATURE = "e".repeat(64);

    @TempDir
    Path tempDirectory;

    @Test
    void provesOneKilledMutantFromTwoControlsAndTwoAuthenticatedReplays() throws Exception {
        Evidence controlA = report("1".repeat(32), ORIGINAL, ExecutionStatus.PASSED, "", "", "");
        Evidence controlB = report("2".repeat(32), ORIGINAL, ExecutionStatus.PASSED, "", "", "");
        Evidence mutantA = assertion("3".repeat(32));
        Evidence mutantB = assertion("4".repeat(32));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = MutationProofMain.run(
                arguments(controlA, mutantA, controlB, mutantB),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, exit);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"state\":\"KILLED\""));
        assertTrue(output.toString(StandardCharsets.UTF_8)
                .contains("\"strictKillRate\":\"100.000000\""));
    }

    @Test
    void refusesTwoRuntimeErrorReplays() throws Exception {
        Evidence controlA = report("1".repeat(32), ORIGINAL, ExecutionStatus.PASSED, "", "", "");
        Evidence controlB = report("2".repeat(32), ORIGINAL, ExecutionStatus.PASSED, "", "", "");
        Evidence mutantA = report("3".repeat(32), MUTANT, ExecutionStatus.RUNTIME_ERROR, "", "", "");
        Evidence mutantB = report("4".repeat(32), MUTANT, ExecutionStatus.RUNTIME_ERROR, "", "", "");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = MutationProofMain.run(
                arguments(controlA, controlB, mutantA, mutantB),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()));

        assertEquals(2, exit);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"state\":\"RUNTIME_ERROR\""));
    }

    @Test
    void returnsUsageErrorForMissingEvidenceArguments() {
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = MutationProofMain.run(
                new String[]{ORIGINAL},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(error, true, StandardCharsets.UTF_8));

        assertEquals(4, exit);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("usage"));
    }

    @Test
    void reportsMalformedTypedEvidenceAsAToolInputError() throws Exception {
        Path malformed = tempDirectory.resolve("malformed.json");
        Files.writeString(malformed, "{}\n", StandardCharsets.UTF_8);
        Evidence invalid = new Evidence(malformed, "1".repeat(64));
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = MutationProofMain.run(
                arguments(invalid, invalid, invalid, invalid),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(error, true, StandardCharsets.UTF_8));

        assertEquals(4, exit);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("junitEventInvalid"));
    }

    @Test
    void rejectsAnInvalidOriginalSourceDigest() {
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        String[] arguments = {"not-a-sha", "a", "1".repeat(64), "b", "2".repeat(64),
                "c", "3".repeat(64), "d", "4".repeat(64)};

        int exit = MutationProofMain.run(
                arguments,
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(error, true, StandardCharsets.UTF_8));

        assertEquals(4, exit);
        assertTrue(error.toString(StandardCharsets.UTF_8)
                .contains("originalSourceDigestInvalid"));
    }

    @Test
    void rejectsAReusedNonceAcrossAuthenticatedReports() throws Exception {
        String nonce = "1".repeat(32);
        Evidence controlA = report(nonce, ORIGINAL, ExecutionStatus.PASSED, "", "", "");
        Evidence controlB = report("2".repeat(32), ORIGINAL, ExecutionStatus.PASSED, "", "", "");
        Evidence mutantA = report(
                nonce, MUTANT, ExecutionStatus.ASSERTION_FAILURE, TEST_ID,
                "org.opentest4j.AssertionFailedError", SIGNATURE);
        Evidence mutantB = assertion("4".repeat(32));
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = MutationProofMain.run(
                arguments(controlA, controlB, mutantA, mutantB),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(error, true, StandardCharsets.UTF_8));

        assertEquals(4, exit);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("executionNonceDuplicate"));
    }

    @Test
    void rejectsOneReportSignedWithAnUnmatchedKey() throws Exception {
        Evidence controlA = report("1".repeat(32), ORIGINAL, ExecutionStatus.PASSED, "", "", "");
        Evidence controlB = report("2".repeat(32), ORIGINAL, ExecutionStatus.PASSED, "", "", "");
        Evidence mutantA = assertion("3".repeat(32));
        Evidence mutantB = assertion("4".repeat(32));
        mutantB = new Evidence(mutantB.path(), "f".repeat(64));

        int exit = MutationProofMain.run(
                arguments(controlA, controlB, mutantA, mutantB),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));

        assertEquals(4, exit);
    }

    private Evidence assertion(String nonce) throws Exception {
        return report(
                nonce,
                MUTANT,
                ExecutionStatus.ASSERTION_FAILURE,
                TEST_ID,
                "org.opentest4j.AssertionFailedError",
                SIGNATURE);
    }

    private Evidence report(
            String nonce,
            String source,
            ExecutionStatus status,
            String testId,
            String assertionType,
            String signature) throws Exception {
        Path report = tempDirectory.resolve(source.substring(0, 1) + "-" + nonce + ".json");
        String key = nonce.substring(0, 1).repeat(64);
        TestExecution execution = new TestExecution(
                status,
                INVENTORY,
                testId,
                assertionType,
                signature,
                nonce,
                false,
                false);
        Files.write(report, new JUnitEventSummary(source, execution).authenticatedFile(key));
        return new Evidence(report, key);
    }

    private static String[] arguments(Evidence... evidence) {
        String[] result = new String[1 + evidence.length * 2];
        result[0] = ORIGINAL;
        for (int index = 0; index < evidence.length; index++) {
            result[index * 2 + 1] = evidence[index].path().toString();
            result[index * 2 + 2] = evidence[index].key();
        }
        return result;
    }

    private record Evidence(Path path, String key) {}
}
