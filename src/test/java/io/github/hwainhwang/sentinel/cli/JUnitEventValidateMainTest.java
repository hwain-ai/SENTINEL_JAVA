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

class JUnitEventValidateMainTest {
    private static final String KEY = "e".repeat(64);

    @TempDir
    Path tempDirectory;

    @Test
    void acceptsAnEventWithTheRequestedNonceAndSource() throws Exception {
        String nonce = "a".repeat(32);
        String source = "b".repeat(64);
        Path event = event(nonce, source);

        int exit = JUnitEventValidateMain.run(
                new String[]{event.toString(), nonce, source, KEY},
                new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, exit);
    }

    @Test
    void rejectsAnEventFromAnotherExecution() throws Exception {
        String nonce = "a".repeat(32);
        String source = "b".repeat(64);
        Path event = event(nonce, source);
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = JUnitEventValidateMain.run(
                new String[]{event.toString(), "c".repeat(32), source, KEY},
                new PrintStream(error, true, StandardCharsets.UTF_8));

        assertEquals(4, exit);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("IdentityMismatch"));
    }

    @Test
    void rejectsAnEventAuthenticatedForAnotherExecutionKey() throws Exception {
        String nonce = "a".repeat(32);
        String source = "b".repeat(64);
        Path event = event(nonce, source);

        int exit = JUnitEventValidateMain.run(
                new String[]{event.toString(), nonce, source, "f".repeat(64)},
                new PrintStream(new ByteArrayOutputStream()));

        assertEquals(4, exit);
    }

    private Path event(String nonce, String source) throws Exception {
        Path event = tempDirectory.resolve("event.json");
        TestExecution execution = new TestExecution(
                ExecutionStatus.PASSED,
                "d".repeat(64),
                "",
                "",
                "",
                nonce,
                false,
                false);
        Files.write(event, new JUnitEventSummary(source, execution).authenticatedFile(KEY));
        return event;
    }
}
