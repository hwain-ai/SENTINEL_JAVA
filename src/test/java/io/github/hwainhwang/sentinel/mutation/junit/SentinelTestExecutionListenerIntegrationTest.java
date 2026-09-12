package io.github.hwainhwang.sentinel.mutation.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import io.github.hwainhwang.sentinel.mutation.ExecutionStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SentinelTestExecutionListenerIntegrationTest {
    private static final Path REQUEST = Path.of("target/sentinel-junit-request-v1");
    private static final String SOURCE = "a".repeat(64);
    private static final String KEY = "b".repeat(64);

    @TempDir
    Path tempDirectory;

    @AfterEach
    void removeRequest() throws Exception {
        Files.deleteIfExists(REQUEST);
        TypedFixture.mode = Mode.PASS;
    }

    @Test
    void emitsAPassingControlFromRealJunitEvents() throws Exception {
        JUnitEventSummary summary = execute(Mode.PASS, "1".repeat(32));

        assertEquals(ExecutionStatus.PASSED, summary.execution().status());
    }

    @Test
    void emitsATypedOpenTest4jAssertionFromRealJunitEvents() throws Exception {
        JUnitEventSummary summary = execute(Mode.ASSERTION, "2".repeat(32));

        assertEquals(ExecutionStatus.ASSERTION_FAILURE, summary.execution().status());
        assertEquals("org.opentest4j.AssertionFailedError", summary.execution().assertionType());
    }

    @Test
    void keepsANonAssertionThrowableOutOfKilledEvidence() throws Exception {
        JUnitEventSummary summary = execute(Mode.RUNTIME, "3".repeat(32));

        assertEquals(ExecutionStatus.RUNTIME_ERROR, summary.execution().status());
    }

    @Test
    void distinguishesDifferentAssertionsFromTheSameJunitTestId() throws Exception {
        JUnitEventSummary first = execute(Mode.ASSERTION, "5".repeat(32));
        JUnitEventSummary second = execute(Mode.ALTERNATE_ASSERTION, "6".repeat(32));

        assertEquals(first.execution().testId(), second.execution().testId());
        assertNotEquals(
                first.execution().failureSignature(),
                second.execution().failureSignature());
    }

    @Test
    void distinguishesEqualMessagesFromDifferentFailureLocations() throws Exception {
        JUnitEventSummary first = execute(Mode.ASSERTION, "7".repeat(32));
        JUnitEventSummary second = execute(Mode.SAME_MESSAGE_OTHER_SITE, "8".repeat(32));
        assertEquals(first.execution().testId(), second.execution().testId());
        assertNotEquals(first.execution().failureSignature(), second.execution().failureSignature());
    }

    @Test
    void keepsTheSameFailureSiteStableAcrossDifferentLauncherCallers() throws Exception {
        JUnitEventSummary first = execute(Mode.ASSERTION, "9".repeat(32));
        JUnitEventSummary second = execute(Mode.ASSERTION, "a".repeat(32));
        assertEquals(first.execution().failureSignature(), second.execution().failureSignature());
    }

    @Test
    void includesAggregateChildFailureLocations() throws Exception {
        var first = execute(Mode.AGGREGATE_ONE, "b".repeat(32));
        var second = execute(Mode.AGGREGATE_OTHER, "c".repeat(32));
        assertEquals(ExecutionStatus.ASSERTION_FAILURE, first.execution().status());
        assertNotEquals(first.execution().failureSignature(), second.execution().failureSignature());
    }

    @Test
    void neverReportsPassedWhenFailureInspectionThrows() throws Exception {
        var summary = execute(Mode.BROKEN_STACK, "d".repeat(32));
        assertTrue(summary.execution().status() == ExecutionStatus.RUNTIME_ERROR
                || summary.execution().status() == ExecutionStatus.TOOL_ERROR);
    }

    @Test
    void capturesTheDeclaringMethodOfAnInheritedTest() throws Exception {
        var summary = execute(Mode.PASS, "e".repeat(32), InheritedFixture.class);
        assertEquals(ExecutionStatus.ASSERTION_FAILURE, summary.execution().status());
    }

    @Test
    void refusesSkippedAbortedAndDynamicMethodContainers() throws Exception {
        int nonce = 16;
        for (Class<?> fixture : java.util.List.of(DisabledFactoryFixture.class,
                DisabledTemplateFixture.class, AbortedFactoryFixture.class, DynamicFactoryFixture.class)) {
            var summary = execute(Mode.PASS, Integer.toHexString(nonce++).repeat(16), fixture);
            assertNotEquals(ExecutionStatus.PASSED, summary.execution().status(), fixture.getName());
            assertNotEquals(ExecutionStatus.ASSERTION_FAILURE, summary.execution().status(), fixture.getName());
        }
    }

    @Test
    void refusesUnsupportedFailuresWrappedByRealJunitAssertAll() throws Exception {
        var summary = execute(Mode.UNSUPPORTED_AGGREGATE, "f".repeat(32));
        assertEquals(ExecutionStatus.RUNTIME_ERROR, summary.execution().status());
    }

    @Test
    void rejectsAnInvalidRequestIdentity() throws Exception {
        prepareDirectory();
        Files.createDirectories(REQUEST.getParent());
        Files.writeString(
                REQUEST,
                request("not-a-nonce", tempDirectory.resolve("bad.json"), "false"),
                StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, JUnitRequest::readIfPresent);
    }

    @Test
    void rejectsAnUnknownCacheObservationInsteadOfGuessing() throws Exception {
        prepareDirectory();
        Files.createDirectories(REQUEST.getParent());
        Files.writeString(
                REQUEST,
                request("4".repeat(32), tempDirectory.resolve("bad-cache.json"), "unknown"),
                StandardCharsets.UTF_8);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, JUnitRequest::readIfPresent);

        assertEquals("junitRequestCacheObservationInvalid", failure.getMessage());
    }

    private JUnitEventSummary execute(Mode mode, String nonce) throws Exception {
        return execute(mode, nonce, TypedFixture.class);
    }

    private JUnitEventSummary execute(Mode mode, String nonce, Class<?> selected) throws Exception {
        prepareDirectory();
        Path event = tempDirectory.resolve(nonce + ".json").toAbsolutePath().normalize();
        Files.createDirectories(REQUEST.getParent());
        Files.writeString(
                REQUEST,
                request(nonce, event, "false"),
                StandardCharsets.UTF_8);
        TypedFixture.mode = mode;
        Launcher launcher = LauncherFactory.create(LauncherConfig.builder()
                .enableTestExecutionListenerAutoRegistration(false)
                .build());
        launcher.registerTestExecutionListeners(new SentinelTestExecutionListener());
        launcher.execute(LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(selected))
                .build());
        return JUnitEventSummary.read(event, KEY);
    }

    private static String request(String nonce, Path event, String cacheObservation) {
        return "sentinel-java-junit-request-v2\n"
                + nonce + "\n" + SOURCE + "\n" + event.toAbsolutePath().normalize() + "\n"
                + KEY + "\ncache-observed=" + cacheObservation + "\n";
    }

    private void prepareDirectory() throws Exception {
        Files.setPosixFilePermissions(
                tempDirectory, PosixFilePermissions.fromString("rwx------"));
        Files.deleteIfExists(REQUEST);
    }

    enum Mode {
        PASS,
        ASSERTION,
        ALTERNATE_ASSERTION,
        SAME_MESSAGE_OTHER_SITE,
        AGGREGATE_ONE,
        AGGREGATE_OTHER,
        UNSUPPORTED_AGGREGATE,
        BROKEN_STACK,
        RUNTIME
    }

    static class TypedFixture {
        static Mode mode = Mode.PASS;

        @Test
        void emitsTheSelectedOutcome() {
            if (mode == Mode.ASSERTION) {
                assertEquals("expected", "actual");
            }
            if (mode == Mode.ALTERNATE_ASSERTION) {
                assertEquals("different", "actual");
            }
            if (mode == Mode.SAME_MESSAGE_OTHER_SITE) {
                assertEquals("expected", "actual");
            }
            if (mode == Mode.RUNTIME) {
                throw new IllegalStateException("runtime failure");
            }
            if (mode == Mode.AGGREGATE_ONE || mode == Mode.AGGREGATE_OTHER) {
                assertAll(() -> {
                    if (mode == Mode.AGGREGATE_ONE) {
                        assertEquals(1, 2);
                    } else {
                        assertEquals(1, 2);
                    }
                });
            }
            if (mode == Mode.BROKEN_STACK) {
                throw new BrokenStackFailure();
            }
            if (mode == Mode.UNSUPPORTED_AGGREGATE) {
                assertAll(() -> { throw new JUnitTypedListenerTest.UnsupportedAssertion(); });
            }
        }
    }

    static class InheritedBase {
        @Test void inherited() { assertEquals(1, 2); }
    }

    static class InheritedFixture extends InheritedBase { }

    static class DisabledFactoryFixture {
        @Test void ordinary() { }
        @Disabled @TestFactory java.util.List<DynamicTest> skipped() { return java.util.List.of(); }
    }

    static class DisabledTemplateFixture {
        @Test void ordinary() { }
        @Disabled @ParameterizedTest @ValueSource(ints = {1}) void skipped(int value) { }
    }

    static class AbortedFactoryFixture {
        @Test void ordinary() { }
        @TestFactory java.util.List<DynamicTest> aborted() { assumeTrue(false); return java.util.List.of(); }
    }

    static class DynamicFactoryFixture {
        @Test void ordinary() { }
        @TestFactory java.util.List<DynamicTest> dynamic() {
            return java.util.List.of(DynamicTest.dynamicTest("dynamic", () -> { }));
        }
    }

    static class BrokenStackFailure extends AssertionError {
        @Override public StackTraceElement[] getStackTrace() {
            throw new IllegalStateException("stack unavailable");
        }
    }
}
