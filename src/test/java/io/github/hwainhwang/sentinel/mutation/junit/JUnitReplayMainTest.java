package io.github.hwainhwang.sentinel.mutation.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.hwainhwang.sentinel.mutation.junit.SentinelTestExecutionListenerIntegrationTest.Mode;
import io.github.hwainhwang.sentinel.mutation.junit.SentinelTestExecutionListenerIntegrationTest.TypedFixture;

class JUnitReplayMainTest {
    @TempDir Path events;

    @AfterEach
    void resetFixture() throws Exception {
        TypedFixture.mode = Mode.PASS;
        Files.deleteIfExists(Path.of("target/sentinel-junit-request-v1"));
    }

    @Test
    void executesRealJupiterAndSignsDifferentResultSets() throws Exception {
        String previous = null;
        for (Mode mode : List.of(Mode.PASS, Mode.ASSERTION, Mode.RUNTIME)) {
            try (var request = request()) {
                TypedFixture.mode = mode;
                assertEquals(mode == Mode.PASS ? 0 : 1, JUnitReplayMain.run(new String[] {TypedFixture.class.getName()}));
                JUnitReplaySummary replay = JUnitReplaySummary.read(request.eventFile(), request.hmacKey());
                assertEquals(request.nonce(), replay.summary().execution().nonce());
                assertEquals(request.sourceSha256(), replay.summary().sourceSha256());
                assertTrue(replay.resultsSha256().matches("[0-9a-f]{64}"));
                if (mode == Mode.RUNTIME) {
                    assertEquals(previous, replay.resultsSha256());
                    assertEquals(io.github.hwainhwang.sentinel.mutation.ExecutionStatus.RUNTIME_ERROR,
                            replay.summary().execution().status());
                } else {
                    assertNotEquals(previous, replay.resultsSha256());
                }
                previous = replay.resultsSha256();
                String output = Files.readString(request.eventFile());
                assertFalse(output.contains("runtime failure"));
                assertFalse(output.contains("expected"));
            }
        }
    }

    @Test
    void rejectsMissingOversizedAlteredAndCrossExecutionResultEnvelopes() throws Exception {
        try (var request = request()) {
            TypedFixture.mode = Mode.PASS;
            JUnitReplayMain.run(new String[] {TypedFixture.class.getName()});
            Path companion = request.eventFile().resolveSibling(request.eventFile().getFileName() + ".replay");
            String original = Files.readString(companion);
            for (String content : List.of("wrong\n", "x".repeat(1025), original.replace("\n", "\r\n"),
                    original.replace(request.nonce(), "0".repeat(32)), original.replace("a".repeat(64), "b".repeat(64)))) {
                Files.writeString(companion, content);
                assertThrows(IllegalArgumentException.class, () -> JUnitReplaySummary.read(request.eventFile(), request.hmacKey()));
            }
            Files.delete(companion);
            assertThrows(IllegalArgumentException.class, () -> JUnitReplaySummary.read(request.eventFile(), request.hmacKey()));
        }
    }

    @Test
    void refusesMissingRequestsAndEmptyClassScopes() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> JUnitReplayMain.run(new String[] {TypedFixture.class.getName()}));
        try (var request = request()) {
            assertThrows(IllegalArgumentException.class, () -> JUnitReplayMain.run(new String[] {}));
        }
        assertThrows(IllegalStateException.class, () -> new SentinelTestExecutionListener().executionStatus());
    }

    private JUnitRequestWriter.Request request() throws Exception {
        Files.setPosixFilePermissions(events, PosixFilePermissions.fromString("rwx------"));
        return JUnitRequestWriter.create(Path.of("").toAbsolutePath().normalize(), events, "a".repeat(64), false);
    }
}
