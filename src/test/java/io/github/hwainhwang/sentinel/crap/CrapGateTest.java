package io.github.hwainhwang.sentinel.crap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CrapGateTest {
    @Test
    void functionSelectionDoesNotJudgeAnUncoveredSiblingMethod() {
        byte[] source = "class Sample {\n int value() { return 1; }\n int other() { return 2; }\n}".getBytes(StandardCharsets.UTF_8);
        byte[] coverage = report("()I", 2, 0).getBytes(StandardCharsets.UTF_8);
        CrapGate.Result result = CrapGate.evaluate(Map.of("Sample.java", source), coverage,
                java.util.List.of(), GateThreshold.DEFAULT_CRAP_MAX, java.util.Set.of("Sample.java"), java.util.Set.of("value"));
        assertEquals(1, result.total());
        assertEquals("value", result.metrics().get(0).callable().identity().callableName());
    }

    @Test
    void rejectsFunctionSelectionThatWouldMutateAnotherMethodOnTheSameLine() {
        byte[] source = "class Sample { int value() { return 1; } int other() { return 2; } }".getBytes(StandardCharsets.UTF_8);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CrapGate.evaluate(Map.of("Sample.java", source), report("()I", 2, 0).getBytes(StandardCharsets.UTF_8),
                        java.util.List.of(), GateThreshold.DEFAULT_CRAP_MAX, java.util.Set.of("Sample.java"), java.util.Set.of("value")));
        assertEquals("functionSelectionInvalid", error.getMessage());
    }
    @Test
    void passesOnlyWhenEveryCallableHasKnownCrapAtMostEight() {
        byte[] source = "class Sample { int value(boolean flag) { return flag ? 1 : 0; } }"
                .getBytes(StandardCharsets.UTF_8);
        byte[] coverage = report("(Z)I", 4, 0).getBytes(StandardCharsets.UTF_8);

        CrapGate.Result result = CrapGate.evaluate(Map.of("Sample.java", source), coverage);

        assertTrue(result.passed());
        assertEquals(1, result.total());
        assertEquals(1, result.known());
        assertEquals(0, result.unknown());
        assertEquals(0, result.aboveLimit());
    }

    @Test
    void failsClosedWhenCoverageCannotJoinAProductionCallable() {
        byte[] source = "class Sample { int value(boolean flag) { return flag ? 1 : 0; } }"
                .getBytes(StandardCharsets.UTF_8);
        byte[] coverage = "<report name=\"sample\"/>".getBytes(StandardCharsets.UTF_8);

        CrapGate.Result result = CrapGate.evaluate(Map.of("Sample.java", source), coverage);

        assertFalse(result.passed());
        assertEquals(1, result.unknown());
        assertEquals("METHOD_MISSING", result.rows().get(0).unknownReason());
    }

    @Test
    void failsOnExactRawCrapAboveEight() {
        byte[] source = ("class Sample { int value(boolean first, boolean second) { "
                + "if (first) return 1; if (second) return 2; return 0; } }")
                .getBytes(StandardCharsets.UTF_8);
        byte[] coverage = report("(ZZ)I", 0, 4).getBytes(StandardCharsets.UTF_8);

        CrapGate.Result result = CrapGate.evaluate(Map.of("Sample.java", source), coverage);

        assertFalse(result.passed());
        assertEquals(1, result.aboveLimit());
        assertEquals("12", result.rows().get(0).numerator().toString());
        assertEquals("1", result.rows().get(0).denominator().toString());
    }

    private static String report(String descriptor, int covered, int missed) {
        return """
                <report name="sample">
                  <package name="">
                    <class name="Sample">
                      <method name="value" desc="%s" line="1">
                        <counter type="INSTRUCTION" covered="%d" missed="%d"/>
                      </method>
                    </class>
                  </package>
                </report>
                """.formatted(descriptor, covered, missed);
    }
}
