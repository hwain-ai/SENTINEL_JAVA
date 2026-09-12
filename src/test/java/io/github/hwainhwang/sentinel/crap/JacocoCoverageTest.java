package io.github.hwainhwang.sentinel.crap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class JacocoCoverageTest {
    @Test
    void acceptsTheExactDoctypeEmittedByPinnedJacoco() {
        JacocoCoverage.Report report = parse("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
                <report name="JaCoCo Coverage Report"/>
                """);

        assertTrue(report.present());
    }

    @Test
    void joinsOverloadsByExactOwnerNameAndJvmDescriptor() {
        List<Models.CallableDefinition> definitions = overloads();
        JacocoCoverage.Report report = parse("""
                <report name="demo"><package name="demo"><class name="demo/Overloads">
                  <method name="work" desc="(I)I" line="3">
                    <counter type="INSTRUCTION" missed="1" covered="3"/>
                  </method>
                  <method name="work" desc="(Ljava/lang/String;)I" line="4">
                    <counter type="INSTRUCTION" missed="0" covered="5"/>
                  </method>
                </class></package></report>
                """);

        List<Models.CallableMetric> metrics = JacocoCoverage.measure(definitions, report);

        assertEquals(2, metrics.size());
        assertMetric(metrics, "(I)I", 3, 4);
        assertMetric(metrics, "(Ljava/lang/String;)I", 5, 5);
    }

    @Test
    void joinsConstructorsByInitNameAndDescriptor() {
        String source = "package demo; class Built { Built(int value) {} }";
        List<Models.CallableDefinition> definitions = JavaAnalyzer.analyze(
                source.getBytes(StandardCharsets.UTF_8), "src/demo/Built.java");
        JacocoCoverage.Report report = parse("""
                <report><package name="demo"><class name="demo/Built">
                  <method name="&lt;init&gt;" desc="(I)V" line="1">
                    <counter type="INSTRUCTION" missed="0" covered="4"/>
                  </method>
                </class></package></report>
                """);

        Models.CallableMetric metric = JacocoCoverage.measure(definitions, report).get(0);

        assertEquals(4, metric.coveredUnits());
        assertEquals(4, metric.totalUnits());
        assertTrue(metric.known());
    }

    @Test
    void missingWrongDescriptorAndDuplicateEntryRemainUnknown() {
        List<Models.CallableDefinition> definitions = overloads();
        JacocoCoverage.Report wrongDescriptor = parse("""
                <report><package name="demo"><class name="demo/Overloads">
                  <method name="work" desc="(J)I" line="3">
                    <counter type="INSTRUCTION" missed="0" covered="1"/>
                  </method>
                </class></package></report>
                """);
        JacocoCoverage.Report duplicate = parse("""
                <report><package name="demo"><class name="demo/Overloads">
                  <method name="work" desc="(I)I" line="3">
                    <counter type="INSTRUCTION" missed="0" covered="1"/>
                  </method>
                  <method name="work" desc="(I)I" line="99">
                    <counter type="INSTRUCTION" missed="0" covered="1"/>
                  </method>
                </class></package></report>
                """);
        JacocoCoverage.Report staleLine = parse("""
                <report><package name="demo"><class name="demo/Overloads">
                  <method name="work" desc="(I)I" line="99">
                    <counter type="INSTRUCTION" missed="0" covered="1"/>
                  </method>
                </class></package></report>
                """);

        assertEquals(
                Models.CoverageUnknownReason.METHOD_MISSING,
                JacocoCoverage.measure(definitions, wrongDescriptor).get(0).unknownReason());
        assertEquals(
                Models.CoverageUnknownReason.METHOD_AMBIGUOUS,
                JacocoCoverage.measure(definitions, duplicate).get(0).unknownReason());
        assertEquals(
                Models.CoverageUnknownReason.METHOD_MISSING,
                JacocoCoverage.measure(definitions, staleLine).get(0).unknownReason());
    }

    @Test
    void missingReportZeroInstructionsAndLambdaMappingRemainUnknown() {
        List<Models.CallableDefinition> definitions = overloads();
        List<Models.CallableMetric> missing =
                JacocoCoverage.measure(definitions, JacocoCoverage.Report.missing());
        assertTrue(missing.stream().allMatch(
                item -> item.unknownReason() == Models.CoverageUnknownReason.REPORT_MISSING));

        JacocoCoverage.Report zero = parse("""
                <report><package name="demo"><class name="demo/Overloads">
                  <method name="work" desc="(I)I" line="3">
                    <counter type="INSTRUCTION" missed="0" covered="0"/>
                  </method>
                </class></package></report>
                """);
        assertEquals(
                Models.CoverageUnknownReason.ZERO_INSTRUCTIONS,
                JacocoCoverage.measure(definitions, zero).get(0).unknownReason());

        String lambdaSource = """
                import java.util.function.Supplier;
                class HasLambda { int value() { Supplier<Integer> s = () -> 1; return s.get(); } }
                """;
        Models.CallableDefinition lambda = JavaAnalyzer.analyze(
                        lambdaSource.getBytes(StandardCharsets.UTF_8), "HasLambda.java")
                .stream()
                .filter(item -> item.identity().kind() == Models.CallableKind.LAMBDA)
                .findFirst()
                .orElseThrow();
        assertEquals(
                Models.CoverageUnknownReason.LAMBDA_MAPPING_UNAVAILABLE,
                JacocoCoverage.measure(List.of(lambda), parse("<report/>"))
                        .get(0)
                        .unknownReason());
    }

    @Test
    void missingOrDuplicateInstructionCounterRemainsUnknown() {
        Models.CallableDefinition definition = overloads().get(0);
        JacocoCoverage.Report missingCounter = parse("""
                <report><package name="demo"><class name="demo/Overloads">
                  <method name="work" desc="(I)I" line="3"/>
                </class></package></report>
                """);
        JacocoCoverage.Report duplicateCounter = parse("""
                <report><package name="demo"><class name="demo/Overloads">
                  <method name="work" desc="(I)I" line="3">
                    <counter type="INSTRUCTION" missed="0" covered="1"/>
                    <counter type="INSTRUCTION" missed="0" covered="1"/>
                  </method>
                </class></package></report>
                """);

        assertEquals(
                Models.CoverageUnknownReason.COUNTER_AMBIGUOUS,
                JacocoCoverage.measure(List.of(definition), missingCounter).get(0).unknownReason());
        assertEquals(
                Models.CoverageUnknownReason.COUNTER_AMBIGUOUS,
                JacocoCoverage.measure(List.of(definition), duplicateCounter).get(0).unknownReason());
    }

    @Test
    void malformedUnsafeOrImpossibleXmlFailsClosed() {
        assertThrows(CoverageFormatException.class, () -> parse("<report>"));
        assertThrows(
                CoverageFormatException.class,
                () -> parse("""
                        <!DOCTYPE report [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                        <report name="&xxe;"/>
                        """));
        assertThrows(
                CoverageFormatException.class,
                () -> parse("""
                        <report><package><class name="demo/Sample">
                          <method name="work" desc="()V" line="1">
                            <counter type="INSTRUCTION" missed="-1" covered="2"/>
                          </method>
                        </class></package></report>
                        """));
    }

    @Test
    void acceptsAnInstructionLineInsideAMultilineMethodRange() {
        String source = """
                package demo;
                class Multiline {
                    int work(
                            int value) {
                        return value;
                    }
                }
                """;
        Models.CallableDefinition definition = JavaAnalyzer.analyze(
                source.getBytes(StandardCharsets.UTF_8), "src/demo/Multiline.java").get(0);
        JacocoCoverage.Report report = parse("""
                <report><package name="demo"><class name="demo/Multiline">
                  <method name="work" desc="(I)I" line="5">
                    <counter type="INSTRUCTION" missed="0" covered="2"/>
                  </method>
                </class></package></report>
                """);

        Models.CallableMetric metric = JacocoCoverage.measure(List.of(definition), report).get(0);

        assertTrue(metric.known());
        assertEquals(2, metric.coveredUnits());
    }

    @Test
    void rejectsMisplacedClassesMalformedDescriptorsAndUnknownCounters() {
        assertThrows(
                CoverageFormatException.class,
                () -> parse("""
                        <report><package name="demo"><class name="demo/Outer">
                          <method name="outer" desc="()V" line="1">
                            <class name="demo/Injected">
                              <method name="work" desc="()V" line="1">
                                <counter type="INSTRUCTION" missed="0" covered="1"/>
                              </method>
                            </class>
                          </method>
                        </class></package></report>
                        """));
        assertThrows(
                CoverageFormatException.class,
                () -> parse("""
                        <report><package name="demo"><class name="../Escaped">
                          <method name="work" desc="()V" line="1">
                            <counter type="INSTRUCTION" missed="0" covered="1"/>
                          </method>
                        </class></package></report>
                        """));
        assertThrows(
                CoverageFormatException.class,
                () -> parse("""
                        <report><package name="demo"><class name="demo/Sample">
                          <method name="work" desc="not-a-descriptor" line="1">
                            <counter type="INSTRUCTION" missed="0" covered="1"/>
                          </method>
                        </class></package></report>
                        """));
        assertThrows(
                CoverageFormatException.class,
                () -> parse("""
                        <report><package name="demo"><class name="demo/Sample">
                          <method name="bad&lt;name" desc="()V" line="1">
                            <counter type="INSTRUCTION" missed="0" covered="1"/>
                          </method>
                        </class></package></report>
                        """));
        assertThrows(
                CoverageFormatException.class,
                () -> parse("""
                        <report>
                          <method name="work" desc="()V" line="1">
                            <counter type="INSTRUCTION" missed="0" covered="1"/>
                          </method>
                        </report>
                        """));
        assertThrows(
                CoverageFormatException.class,
                () -> parse("""
                        <report><counter type="LINE" missed="-1" covered="1"/></report>
                        """));
        assertThrows(
                CoverageFormatException.class,
                () -> parse("""
                        <report><package name="demo"><class name="demo/Sample">
                          <method name="work" desc="()V" line="1">
                            <counter type="UNKNOWN" missed="0" covered="1"/>
                            <counter type="INSTRUCTION" missed="0" covered="1"/>
                          </method>
                        </class></package></report>
                        """));
    }

    @Test
    void rejectsElementsOutsideThePinnedJacocoStructure() {
        assertThrows(
                CoverageFormatException.class,
                () -> parse("""
                        <report><wrapper><package name="demo"><class name="demo/Sample">
                          <method name="work" desc="()V" line="1">
                            <counter type="INSTRUCTION" missed="0" covered="1"/>
                          </method>
                        </class></package></wrapper></report>
                        """));
        assertThrows(
                CoverageFormatException.class,
                () -> parse("""
                        <report><package name="demo"><class name="demo/Sample">
                          <ignored/>
                          <method name="work" desc="()V" line="1">
                            <counter type="INSTRUCTION" missed="0" covered="1"/>
                          </method>
                        </class></package></report>
                        """));
    }

    @Test
    void rejectsImpossibleJvmSpecialMethodDescriptors() {
        assertThrows(
                CoverageFormatException.class,
                () -> parse("""
                        <report><package name="demo"><class name="demo/Sample">
                          <method name="&lt;init&gt;" desc="()I" line="1">
                            <counter type="INSTRUCTION" missed="0" covered="1"/>
                          </method>
                        </class></package></report>
                        """));
        assertThrows(
                CoverageFormatException.class,
                () -> parse("""
                        <report><package name="demo"><class name="demo/Sample">
                          <method name="&lt;clinit&gt;" desc="(I)V" line="1">
                            <counter type="INSTRUCTION" missed="0" covered="1"/>
                          </method>
                        </class></package></report>
                        """));
    }

    @Test
    void malformedXmlDoesNotWriteParserDiagnosticsToProcessStderr() {
        PrintStream previous = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            assertThrows(CoverageFormatException.class, () -> parse("<report>"));
        } finally {
            System.setErr(previous);
        }
        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    void publicMetricCannotBypassTheSafeIntegerCountBoundary() {
        Models.CallableDefinition definition = overloads().get(0);
        ExactCrap validScore = ExactCrap.calculate(1, 1, 1);

        assertThrows(
                IllegalArgumentException.class,
                () -> new Models.CallableMetric(
                        definition,
                        Models.MAX_SAFE_INTEGER + 1,
                        Models.MAX_SAFE_INTEGER + 1,
                        validScore,
                        null));
    }

    @Test
    void predictableCompilerAddedConstructorParametersJoinExactly() {
        String source = """
                package demo;
                class Outer {
                    class Inner { Inner(int value) {} }
                }
                enum Mode { ACTIVE; Mode() {} }
                """;
        List<Models.CallableDefinition> constructors = JavaAnalyzer.analyze(
                        source.getBytes(StandardCharsets.UTF_8), "src/demo/Outer.java")
                .stream()
                .filter(item -> item.identity().kind() == Models.CallableKind.CONSTRUCTOR)
                .toList();
        JacocoCoverage.Report report = parse("""
                <report><package name="demo">
                  <class name="demo/Outer$Inner">
                    <method name="&lt;init&gt;" desc="(Ldemo/Outer;I)V" line="3">
                      <counter type="INSTRUCTION" missed="0" covered="1"/>
                    </method>
                  </class>
                  <class name="demo/Mode">
                    <method name="&lt;init&gt;" desc="(Ljava/lang/String;I)V" line="5">
                      <counter type="INSTRUCTION" missed="0" covered="1"/>
                    </method>
                  </class>
                </package></report>
                """);

        List<Models.CallableMetric> metrics =
                JacocoCoverage.measure(constructors, report);

        assertEquals(2, metrics.size());
        assertTrue(metrics.stream().allMatch(Models.CallableMetric::known));
        assertTrue(constructors.stream().anyMatch(item ->
                item.identity().descriptor().equals("(Ldemo/Outer;I)V")));
        assertTrue(constructors.stream().anyMatch(item ->
                item.identity().descriptor().equals("(Ljava/lang/String;I)V")));
    }

    private static List<Models.CallableDefinition> overloads() {
        String source = """
                package demo;
                class Overloads {
                    int work(int value) { return value; }
                    int work(String value) { return value.length(); }
                }
                """;
        return JavaAnalyzer.analyze(
                source.getBytes(StandardCharsets.UTF_8), "src/demo/Overloads.java");
    }

    private static JacocoCoverage.Report parse(String xml) {
        return JacocoCoverage.parse(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertMetric(
            List<Models.CallableMetric> metrics, String descriptor, long covered, long total) {
        Models.CallableMetric metric = metrics.stream()
                .filter(item -> item.callable().identity().descriptor().equals(descriptor))
                .findFirst()
                .orElseThrow();
        assertEquals(covered, metric.coveredUnits());
        assertEquals(total, metric.totalUnits());
        assertTrue(metric.known());
    }
}
