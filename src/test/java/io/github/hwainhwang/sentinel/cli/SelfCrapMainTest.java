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
    @TempDir
    Path projectRoot;

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
        assertEquals(
                "{\"schemaVersion\":\"sentinel-java-self-crap-v1\",\"passed\":true,"
                        + "\"total\":1,\"known\":1,\"unknown\":0,\"aboveLimit\":0}\n",
                standardOut.toString(StandardCharsets.UTF_8));
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
