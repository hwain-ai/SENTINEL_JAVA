package io.github.hwainhwang.sentinel.mutation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Closed argv/environment, bounded waiting and best-effort process-group cleanup on Linux. */
final class PitProbeProcess {
    private PitProbeProcess() {
        throw new AssertionError("no instances");
    }

    static Path javaHome() {
        return Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
    }

    static int run(List<String> arguments, Path root, Path report, long deadline)
            throws IOException, InterruptedException {
        check(deadline);
        Process process = builder(arguments, root).start();
        Thread shutdown = new Shutdown(process);
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                check(deadline);
                checkReportSize(report);
            }
            check(deadline);
            checkReportSize(report);
            return process.exitValue();
        } finally {
            stop(process);
            Runtime.getRuntime().removeShutdownHook(shutdown);
        }
    }

    private static ProcessBuilder builder(List<String> arguments, Path root) throws IOException {
        List<String> argv = new ArrayList<>(List.of("/usr/bin/setsid", "--wait"));
        argv.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(argv).directory(root.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        Map<String, String> environment = builder.environment();
        environment.clear();
        Path home = Files.createDirectories(root.resolve("target/pit-home"));
        environment.put("HOME", home.toString());
        environment.put("JAVA_HOME", javaHome().toString());
        environment.put("PATH", javaHome().resolve("bin") + ":/usr/bin:/bin");
        environment.put("LANG", "C.UTF-8");
        environment.put("LC_ALL", "C.UTF-8");
        return builder;
    }

    static void check(long deadline) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("pitProbeCancelled");
        }
        if (System.nanoTime() >= deadline) {
            throw new IllegalArgumentException("pitProbeTimedOut");
        }
    }

    private static void checkReportSize(Path report) throws IOException {
        if (report != null && Files.exists(report) && Files.size(report) > PitProbeFiles.MAX_REPORT_BYTES) {
            throw new IllegalArgumentException("pitProbeReportTooLarge");
        }
    }

    private static void stop(Process process) {
        // RISK(side-effect): only the new setsid process group is targeted, never the caller's group.
        try {
            Process killer = new ProcessBuilder("/bin/kill", "-KILL", "--", "-" + process.pid())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            killer.waitFor(1, TimeUnit.SECONDS);
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
        } catch (IOException error) {
            process.destroyForcibly();
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static final class Shutdown extends Thread {
        private final Process process;

        Shutdown(Process process) {
            super("sentinel-pit-child-cleanup");
            this.process = process;
        }

        @Override
        public void run() {
            PitProbeProcess.stop(process);
        }
    }
}
