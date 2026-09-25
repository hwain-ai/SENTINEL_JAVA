package io.github.hwainhwang.sentinel.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelfCrapMainTest {
    @Test
    void rejectsIncompleteMeasurementAndListingRequests() {
        for (String[] arguments : new String[][]{
                {}, {"--list-functions"}, {"--list-functions", projectRoot.toString()},
                {projectRoot.toString(), "src/main/java"}}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream error = new ByteArrayOutputStream();
            int exit = SelfCrapMain.run(arguments, new PrintStream(output), new PrintStream(error));
            assertEquals(4, exit);
            assertEquals("", output.toString(StandardCharsets.UTF_8));
            assertTrue(error.toString(StandardCharsets.UTF_8).contains("self-crap error: usage"));
        }
    }

    @TempDir
    Path projectRoot;

    @Test
    void selectsFunctionLinesBeforeCoverageOrTestsExist() throws Exception {
        Path sourceRoot = projectRoot.resolve("src/main/java");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Sample.java"),
                "class Sample {\n int value() { return 1; }\n int other() { return 2; }\n}\n");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exit = SelfCrapMain.run(new String[]{"--list-functions", "--function", "Sample.value",
                projectRoot.toString(), "src/main/java"}, new PrintStream(output), new PrintStream(errors));
        assertEquals(0, exit, errors.toString());
        assertTrue(output.toString().contains("\"function\":\"Sample.value\""));
        assertTrue(output.toString().contains("\"line\":2"));
        assertTrue(!output.toString().contains("Sample.other"));
        assertTrue(!Files.exists(projectRoot.resolve("target")));
    }

    @Test
    void readsProductionSourcesAndPrintsMachineReadablePassingSummary() throws Exception {
        Path sourceRoot = projectRoot.resolve("src/main/java");
        Files.createDirectories(sourceRoot);
        Files.writeString(
                sourceRoot.resolve("Sample.java"),
                "class Sample { int value() { return 1; } }",
                StandardCharsets.UTF_8);
        Path report = projectRoot.resolve("target/jacoco.xml");
        Files.createDirectories(report.getParent());
        Files.writeString(report, report("()I", 2, 0), StandardCharsets.UTF_8);
        ByteArrayOutputStream standardOut = new ByteArrayOutputStream();
        ByteArrayOutputStream standardError = new ByteArrayOutputStream();

        int exit = SelfCrapMain.run(
                new String[]{projectRoot.toString(), "src/main/java", "target/jacoco.xml"},
                new PrintStream(standardOut, true, StandardCharsets.UTF_8),
                new PrintStream(standardError, true, StandardCharsets.UTF_8));

        assertEquals(0, exit);
        assertEquals("", standardError.toString(StandardCharsets.UTF_8));
        String json = standardOut.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"passed\":true"));
        assertTrue(json.contains("\"function\":\"Sample.value\""));
        assertTrue(json.contains("\"score\":\"1\""));
        assertTrue(json.contains("\"coverageBasis\":\"jacoco-instruction\""));
        assertTrue(json.contains("\"total\":1"));
    }

    @Test
    void acceptsAnExplicitCrapLimitBeforeThePositionalArguments() throws Exception {
        Path sourceRoot = projectRoot.resolve("src/main/java");
        Files.createDirectories(sourceRoot);
        Files.writeString(
                sourceRoot.resolve("Sample.java"),
                "class Sample { int value() { return 1; } }",
                StandardCharsets.UTF_8);
        Path report = projectRoot.resolve("target/jacoco.xml");
        Files.createDirectories(report.getParent());
        Files.writeString(report, report("()I", 2, 0), StandardCharsets.UTF_8);
        ByteArrayOutputStream standardOut = new ByteArrayOutputStream();
        ByteArrayOutputStream standardError = new ByteArrayOutputStream();

        int exit = SelfCrapMain.run(
                new String[]{"--crap-max", "0.5", projectRoot.toString(), "src/main/java", "target/jacoco.xml"},
                new PrintStream(standardOut, true, StandardCharsets.UTF_8),
                new PrintStream(standardError, true, StandardCharsets.UTF_8));

        assertEquals(2, exit);
        assertTrue(standardOut.toString(StandardCharsets.UTF_8).contains("\"crapMax\":\"0.5\""));
        assertTrue(standardOut.toString(StandardCharsets.UTF_8).contains("\"aboveLimit\":1"));
        assertTrue(standardError.toString(StandardCharsets.UTF_8).startsWith("CRAP_ABOVE "));

        int only = SelfCrapMain.run(
                new String[]{"--only", "src/main/java/Other.java", projectRoot.toString(), "src/main/java", "target/jacoco.xml"},
                new PrintStream(standardOut, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()));
        assertEquals(2, only);
        assertTrue(standardOut.toString(StandardCharsets.UTF_8).contains("\"total\":0"));

        int kept = SelfCrapMain.run(
                new String[]{"--only", "src/main/java/Sample.java", "--crap-max", "9", projectRoot.toString(), "src/main/java", "target/jacoco.xml"},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));
        assertEquals(0, kept);

        int invalid = SelfCrapMain.run(
                new String[]{"--crap-max", "8.", projectRoot.toString(), "src/main/java", "target/jacoco.xml"},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(standardError, true, StandardCharsets.UTF_8));
        assertEquals(4, invalid);
        assertTrue(standardError.toString(StandardCharsets.UTF_8).contains("crapMaxInvalid"));
    }

    @Test
    void selectsANamedFunctionAndRejectsAnUnknownOption() throws Exception {
        Path sourceRoot = projectRoot.resolve("src/main/java");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Sample.java"),
                "class Sample { int value() { return 1; }\n int other() { return 2; } }",
                StandardCharsets.UTF_8);
        Path coverage = projectRoot.resolve("target/jacoco.xml");
        Files.createDirectories(coverage.getParent());
        Files.writeString(coverage, report("()I", 2, 0), StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int selected = SelfCrapMain.run(new String[]{
                    "--function", "value", "--only", "src/main/java/Sample.java",
                    projectRoot.toString(), "src/main/java", "target/jacoco.xml"},
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8));
        assertEquals(0, selected);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"total\":1"));
        assertEquals("", error.toString(StandardCharsets.UTF_8));

        int rejected = SelfCrapMain.run(new String[]{"--unknown", "value", projectRoot.toString(),
                    "src/main/java", "target/jacoco.xml"},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(error, true, StandardCharsets.UTF_8));
        assertEquals(4, rejected);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("usage"));
    }

    @Test
    void returnsGateFailureAndIdentifiesUnknownCallable() throws Exception {
        Path sourceRoot = projectRoot.resolve("src/main/java");
        Files.createDirectories(sourceRoot);
        Files.writeString(
                sourceRoot.resolve("Sample.java"),
                "class Sample { int value() { return 1; } }",
                StandardCharsets.UTF_8);
        Path report = projectRoot.resolve("target/jacoco.xml");
        Files.createDirectories(report.getParent());
        Files.writeString(report, "<report name=\"sample\"/>", StandardCharsets.UTF_8);
        ByteArrayOutputStream standardOut = new ByteArrayOutputStream();
        ByteArrayOutputStream standardError = new ByteArrayOutputStream();

        int exit = SelfCrapMain.run(
                new String[]{projectRoot.toString(), "src/main/java", "target/jacoco.xml"},
                new PrintStream(standardOut, true, StandardCharsets.UTF_8),
                new PrintStream(standardError, true, StandardCharsets.UTF_8));

        assertEquals(2, exit);
        assertTrue(standardOut.toString(StandardCharsets.UTF_8).contains("\"passed\":false"));
        assertTrue(standardError.toString(StandardCharsets.UTF_8).contains("METHOD_MISSING"));
    }

    @Test
    void rejectsSourceSymlinksInsteadOfReadingOutsideTheProject() throws Exception {
        Path sourceRoot = projectRoot.resolve("src/main/java");
        Files.createDirectories(sourceRoot);
        Path outside = projectRoot.resolve("outside.java");
        Files.writeString(outside, "class Outside {}", StandardCharsets.UTF_8);
        Files.createSymbolicLink(sourceRoot.resolve("Escaped.java"), outside);
        Path report = projectRoot.resolve("target/jacoco.xml");
        Files.createDirectories(report.getParent());
        Files.writeString(report, "<report name=\"sample\"/>", StandardCharsets.UTF_8);
        ByteArrayOutputStream standardError = new ByteArrayOutputStream();

        int exit = SelfCrapMain.run(
                new String[]{projectRoot.toString(), "src/main/java", "target/jacoco.xml"},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(standardError, true, StandardCharsets.UTF_8));

        assertEquals(4, exit);
        assertTrue(standardError.toString(StandardCharsets.UTF_8).contains("sourceSymlinkRejected"));
    }

    @Test
    void acceptsAnExplicitProjectRelativeDependencyClasspath() throws Exception {
        Path sourceRoot = projectRoot.resolve("src/main/java");
        Path dependency = projectRoot.resolve("dependency-classes");
        Files.createDirectories(sourceRoot);
        Files.createDirectories(dependency);
        Files.writeString(
                sourceRoot.resolve("Sample.java"),
                "class Sample { int value() { return 1; } }",
                StandardCharsets.UTF_8);
        Path report = projectRoot.resolve("target/jacoco.xml");
        Files.createDirectories(report.getParent());
        Files.writeString(report, report("()I", 2, 0), StandardCharsets.UTF_8);

        int exit = SelfCrapMain.run(
                new String[]{
                    projectRoot.toString(),
                    "src/main/java",
                    "target/jacoco.xml",
                    "dependency-classes"
                },
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, exit);
    }

    private static String report(String descriptor, int covered, int missed) {
        return """
                <report name="sample"><package name=""><class name="Sample">
                  <method name="value" desc="%s" line="1">
                    <counter type="INSTRUCTION" covered="%d" missed="%d"/>
                  </method>
                </class></package></report>
                """.formatted(descriptor, covered, missed);
    }
}
