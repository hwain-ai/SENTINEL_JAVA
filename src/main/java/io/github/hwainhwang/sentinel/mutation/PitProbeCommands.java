package io.github.hwainhwang.sentinel.mutation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Fixed upstream javac/JUnit/PIT command contracts for the non-certified experimental profile. */
final class PitProbeCommands {
    static final String MUTATORS = "CONDITIONALS_BOUNDARY,NEGATE_CONDITIONALS,TRUE_RETURNS";

    private PitProbeCommands() {
        throw new AssertionError("no instances");
    }

    static void compile(PitProbeWorkspace workspace, PitProbeArtifacts artifacts, long deadline)
            throws IOException, InterruptedException {
        Path main = Files.createDirectories(workspace.root().resolve("target/classes"));
        Path tests = Files.createDirectories(workspace.root().resolve("target/test-classes"));
        compileSources(workspace, workspace.sources("src/main/java/"), main, "", deadline);
        compileSources(workspace, workspace.sources("src/test/java/"), tests,
                main + java.io.File.pathSeparator + artifacts.console(), deadline);
        workspace.sealClasses(deadline);
    }

    private static void compileSources(PitProbeWorkspace workspace, List<String> sources,
            Path destination, String classpath, long deadline) throws IOException, InterruptedException {
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("pitProbeSourceMissing");
        }
        List<String> argv = new ArrayList<>(List.of(PitProbeProcess.javaHome().resolve("bin/javac").toString(),
                "-J-Xmx256m", "--release", "17", "-g", "-proc:none", "-encoding", "UTF-8",
                "-sourcepath", "", "-classpath", classpath, "-d", destination.toString()));
        argv.addAll(sources);
        if (PitProbeProcess.run(argv, workspace.root(), null, deadline) != 0) {
            throw new IllegalArgumentException("pitProbeCompileFailed");
        }
    }

    static void baseline(PitProbeWorkspace workspace, PitProbeArtifacts artifacts,
            List<String> testClasses, long deadline) throws IOException, InterruptedException {
        List<String> argv = javaCommand();
        argv.addAll(List.of("-jar", artifacts.console().toString(), "execute", "--disable-banner",
                "--disable-ansi-colors", "--fail-if-no-tests", "--class-path", testClasspath(workspace.root())));
        for (String test : testClasses) {
            argv.add("--select-class=" + test);
        }
        int exit = PitProbeProcess.run(argv, workspace.root(), null, deadline);
        workspace.verify(deadline);
        if (exit != 0) {
            throw new IllegalArgumentException("pitProbeBaselineFailed");
        }
    }

    static PitReport execute(PitProbeWorkspace workspace, PitProbeArtifacts artifacts,
            PitProbe.Request request, boolean discovery, long deadline) throws IOException, InterruptedException {
        Path reportDirectory = workspace.root().resolve("target/pit-report");
        Path report = reportDirectory.resolve("mutations.xml");
        List<String> argv = javaCommand();
        argv.addAll(List.of("-cp", artifacts.classpath(),
                "org.pitest.mutationtest.commandline.MutationCoverageReport",
                "--reportDir", reportDirectory.toString(), "--targetClasses", String.join(",", request.targetClasses()),
                "--targetTests", String.join(",", request.testClasses()),
                "--sourceDirs", workspace.root().resolve("src/main/java").toString(),
                "--classPath", testClasspath(workspace.root()).replace(java.io.File.pathSeparator, ",")
                        + "," + artifacts.console(),
                "--outputFormats", "XML", "--timestampedReports", "false", "--threads", "1",
                "--mutators", MUTATORS, "--dryRun", Boolean.toString(discovery),
                "--failWhenNoMutations", "false", "--skipFailingTests", "false", "--fullMutationMatrix", "false",
                "--jvmArgs", "-Xmx256m", "--timeoutConst", "2000"));
        int exit = PitProbeProcess.run(argv, workspace.root(), report, deadline);
        workspace.verify(deadline);
        if (exit != 0) {
            throw new IllegalArgumentException("pitProbeBackendFailed");
        }
        return PitReportAdapter.parse(PitProbeFiles.read(report,
                PitProbeFiles.MAX_REPORT_BYTES, "pitProbeReportTooLarge"));
    }

    static List<String> javaCommand() {
        return new ArrayList<>(List.of(PitProbeProcess.javaHome().resolve("bin/java").toString(), "-Xmx256m"));
    }

    static String testClasspath(Path root) {
        return root.resolve("target/classes") + java.io.File.pathSeparator + root.resolve("target/test-classes");
    }
}
