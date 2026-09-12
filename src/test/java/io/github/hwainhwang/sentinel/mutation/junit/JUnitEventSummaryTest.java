package io.github.hwainhwang.sentinel.mutation.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.hwainhwang.sentinel.mutation.ExecutionStatus;
import io.github.hwainhwang.sentinel.mutation.TestExecution;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JUnitEventSummaryTest {
    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final String SOURCE = "a".repeat(64);
    private static final String INVENTORY = "b".repeat(64);
    private static final String TEST_ID = "c".repeat(64);
    private static final String SIGNATURE = "d".repeat(64);
    private static final String KEY = "f".repeat(64);

    @TempDir
    Path tempDirectory;

    @Test
    void parsesTheCanonicalAuthenticatedAssertionSummary() throws Exception {
        Path report = report(canonicalAssertion());

        JUnitEventSummary summary = JUnitEventSummary.read(report, KEY);

        assertEquals(SOURCE, summary.sourceSha256());
        assertEquals(ExecutionStatus.ASSERTION_FAILURE, summary.execution().status());
        assertEquals("org.opentest4j.AssertionFailedError", summary.execution().assertionType());
    }

    @Test
    void rejectsAReportWithTrailingUntrustedContent() throws Exception {
        Path report = report(canonicalText() + "{}\n");

        assertThrows(IllegalArgumentException.class, () -> JUnitEventSummary.read(report, KEY));
    }

    @Test
    void rejectsAssertionStatusWithoutTypedFailureFields() {
        TestExecution invalid = execution(
                ExecutionStatus.ASSERTION_FAILURE, "", "", "");

        assertThrows(
                IllegalArgumentException.class,
                () -> new JUnitEventSummary(SOURCE, invalid).authenticatedFile(KEY));
    }

    @Test
    void rejectsFailureFieldsOnAPassingControl() {
        TestExecution invalid = execution(
                ExecutionStatus.PASSED,
                TEST_ID,
                "org.opentest4j.AssertionFailedError",
                SIGNATURE);

        assertThrows(
                IllegalArgumentException.class,
                () -> new JUnitEventSummary(SOURCE, invalid).authenticatedFile(KEY));
    }

    @Test
    void rejectsAnUnauthenticatedChangeToTheFailureSignature() throws Exception {
        Path report = report(canonicalText().replace(SIGNATURE, "e".repeat(64)));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> JUnitEventSummary.read(report, KEY));

        assertEquals("junitEventHmacInvalid", failure.getMessage());
    }

    @Test
    void rejectsTheRightEventWithTheWrongExecutionKey() throws Exception {
        Path report = report(canonicalAssertion());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> JUnitEventSummary.read(report, "0".repeat(64)));

        assertEquals("junitEventHmacInvalid", failure.getMessage());
    }

    @Test
    void rejectsAnEventWhoseHmacFieldIsMissing() throws Exception {
        String missing = canonicalText().replaceFirst(
                ",\\\"eventHmac\\\":\\\"[0-9a-f]{64}\\\"", "");
        Path report = report(missing);

        assertThrows(IllegalArgumentException.class, () -> JUnitEventSummary.read(report, KEY));
    }

    @Test
    void rejectsAnEventWhoseHmacFieldIsDuplicated() throws Exception {
        String duplicate = canonicalText().replace(
                "\"eventHmac\":",
                "\"eventHmac\":\"" + "0".repeat(64) + "\",\"eventHmac\":");
        Path report = report(duplicate);

        assertThrows(IllegalArgumentException.class, () -> JUnitEventSummary.read(report, KEY));
    }

    private Path report(byte[] value) throws Exception {
        Path report = tempDirectory.resolve("event.json");
        Files.write(report, value);
        return report;
    }

    private Path report(String value) throws Exception {
        return report(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] canonicalAssertion() {
        return summary().authenticatedFile(KEY);
    }

    private static String canonicalText() {
        return new String(canonicalAssertion(), StandardCharsets.UTF_8);
    }

    private static JUnitEventSummary summary() {
        return new JUnitEventSummary(
                SOURCE,
                execution(
                        ExecutionStatus.ASSERTION_FAILURE,
                        TEST_ID,
                        "org.opentest4j.AssertionFailedError",
                        SIGNATURE));
    }

    private static TestExecution execution(
            ExecutionStatus status, String testId, String assertionType, String signature) {
        return new TestExecution(
                status,
                INVENTORY,
                testId,
                assertionType,
                signature,
                NONCE,
                false,
                false);
    }
}
