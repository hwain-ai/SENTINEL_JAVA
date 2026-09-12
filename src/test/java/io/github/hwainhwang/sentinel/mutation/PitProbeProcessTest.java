package io.github.hwainhwang.sentinel.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PitProbeProcessTest {
    @TempDir Path root;

    @Test
    void interruptionStopsNormalDescendantsBeforeTheyCanWriteLater() throws Exception {
        Path started = root.resolve("started");
        Path leaked = root.resolve("leaked");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                PitProbeProcess.run(List.of("/bin/sh", "-c",
                        "(sleep 1; touch leaked) & touch started; wait"), root, null, deadline());
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        worker.start();
        long waiting = System.nanoTime() + 3_000_000_000L;
        while (!Files.exists(started) && worker.isAlive() && System.nanoTime() < waiting) {
            Thread.sleep(10);
        }
        assertTrue(Files.exists(started));
        worker.interrupt();
        worker.join(3000);
        assertFalse(worker.isAlive());
        assertInstanceOf(InterruptedException.class, failure.get());
        Thread.sleep(1200);
        assertFalse(Files.exists(leaked));
    }

    @Test
    void rejectsOversizedReportWhileWaitingAndDoesNotKeepChildRunning() throws Exception {
        Path report = root.resolve("mutations.xml");
        Files.write(report, new byte[PitProbeFiles.MAX_REPORT_BYTES + 1]);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PitProbeProcess.run(List.of("/bin/sh", "-c", "sleep 5; touch leaked"),
                        root, report, deadline()));
        assertEquals("pitProbeReportTooLarge", error.getMessage());
        assertFalse(Files.exists(root.resolve("leaked")));
    }

    @Test
    void childReceivesOnlyClosedEnvironmentAndPrivateWorkingHome() throws Exception {
        assertEquals(0, PitProbeProcess.run(List.of("/bin/sh", "-c",
                "test \"$HOME\" = \"$PWD/target/pit-home\" && test -z \"$MAVEN_OPTS\" "
                        + "&& test -z \"$JAVA_TOOL_OPTIONS\" && test -z \"$AWS_PROFILE\""),
                root, null, deadline()));
    }

    private static long deadline() {
        return System.nanoTime() + 10_000_000_000L;
    }
}
