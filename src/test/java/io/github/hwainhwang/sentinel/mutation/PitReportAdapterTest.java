package io.github.hwainhwang.sentinel.mutation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PitReportAdapterTest {
    @Test
    void exposesAJavaNativePitXmlParser() {
        assertDoesNotThrow(() -> Class.forName(
                "io.github.hwainhwang.sentinel.mutation.PitReportAdapter")
                .getMethod("parse", byte[].class));
    }

    @Test
    void readsTheActualPit130ToyProbeWithTwoSurvivors() throws Exception {
        try (var input = getClass().getResourceAsStream("/pit-1.30.0/observed-weak.xml")) {
            PitReport report = PitReportAdapter.parse(input.readAllBytes());
            assertEquals(List.of(PitReport.Status.SURVIVED, PitReport.Status.KILLED, PitReport.Status.SURVIVED),
                    report.results().stream().map(PitReport.Result::status).toList());
            assertEquals(List.of(List.of(4), List.of(4), List.of(12)),
                    report.results().stream().map(result -> result.identity().indexes()).toList());
            assertTrue(report.partialCoverage());
        }
    }

    @Test
    void readsObservedPit130ShapeWithoutTreatingPartialCoverageAsIncompleteResults() {
        PitReport report = parse(report(mutation("KILLED", true)));
        PitReport.Result result = report.results().get(0);
        assertTrue(report.partialCoverage());
        assertEquals("example.Value", result.identity().mutatedClass());
        assertEquals("positive", result.identity().mutatedMethod());
        assertEquals("(I)Z", result.identity().methodDescription());
        assertEquals("example.BoundaryMutator", result.identity().mutator());
        assertEquals(List.of(4), result.identity().indexes());
        assertEquals(List.of(0), result.blocks());
        assertEquals("Value.java", result.sourceFile());
        assertEquals(5, result.lineNumber());
        assertEquals(1, result.numberOfTestsRun());
        assertEquals("changed boundary", result.description());
        assertEquals("example.ValueTest#boundary", result.killingTest());
        assertEquals(PitReport.Status.KILLED, result.status());
        assertEquals(report.results(), report.joinInventory(List.of(result.identity())));
        assertFalse(parse(report("").replace("partial=\"true\"", "partial=\"false\""))
                .partialCoverage());
    }

    @Test
    void preservesSurvivorsAndEmptyKillingTest() {
        PitReport report = parse(report(mutation("SURVIVED", false)
                .replace("<killingTest>example.ValueTest#boundary</killingTest>", "<killingTest/>")));
        var result = report.results().get(0);
        assertEquals("", result.killingTest());
        assertEquals(PitReport.Status.SURVIVED, result.status());
        var records = report.normalize(Map.of(result.identity(), candidate(1)), Map.of());
        assertEquals(MutationState.SURVIVED, records.get(0).state());
        assertEquals(false, MutationGate.component(records).get("pass"));
    }

    @ParameterizedTest
    @CsvSource({"TIMED_OUT,true,TIMED_OUT", "NON_VIABLE,true,TOOL_ERROR",
            "MEMORY_ERROR,true,RUNTIME_ERROR", "NOT_STARTED,false,PENDING",
            "STARTED,false,PENDING", "RUN_ERROR,true,RUNTIME_ERROR",
            "NO_COVERAGE,false,UNCOVERED", "EQUIVALENT,true,IGNORED"})
    void mapsEveryNonKilledStateWithoutPromotingDetectedToKilled(
            String status, boolean detected, MutationState expectedState) {
        PitReport report = parse(report(mutation(status, detected)));
        var records = report.normalize(
                Map.of(report.results().get(0).identity(), candidate(1)), Map.of());
        assertEquals(expectedState, records.get(0).state());
        assertEquals(false, MutationGate.component(records).get("pass"));
    }

    @Test
    void rejectsMissingProofEvenWhenAllPitResultsAreKilled() {
        PitReport report = parse(report(mutation("KILLED", true)));
        assertCode("pitProofMissing", () -> report.normalize(
                Map.of(report.results().get(0).identity(), candidate(1)), Map.of()));
    }

    @Test
    void usesExistingTypedReplayProofInsteadOfKillingTestText() {
        PitReport report = parse(report(mutation("KILLED", true)));
        var identity = report.results().get(0).identity();
        var expected = Map.of(identity, candidate(1));
        var records = report.normalize(expected, Map.of(identity, proof(ExecutionStatus.ASSERTION_FAILURE)));
        assertEquals(MutationState.KILLED, records.get(0).state());
        assertEquals(MutationState.RUNTIME_ERROR, report.normalize(expected,
                Map.of(identity, proof(ExecutionStatus.RUNTIME_ERROR))).get(0).state());
        assertEquals(MutationState.COMPILE_ERROR, report.normalize(expected,
                Map.of(identity, proof(ExecutionStatus.COMPILE_ERROR))).get(0).state());
        assertEquals(MutationState.TIMED_OUT, report.normalize(expected,
                Map.of(identity, proof(ExecutionStatus.TIMED_OUT))).get(0).state());
    }

    @Test
    void joinsExactInventoryAndRejectsDuplicatesOmissionsAndUnexpectedResults() {
        PitReport report = parse(report(mutation("SURVIVED", false)));
        var identity = report.results().get(0).identity();
        var other = new PitReport.Identity("example.Other", "positive", "(I)Z",
                "example.BoundaryMutator", List.of(4));
        assertCode("pitInventoryMismatch", () -> report.joinInventory(List.of()));
        assertCode("pitInventoryMismatch", () -> report.joinInventory(List.of(identity, other)));
        assertCode("pitInventoryMismatch", () -> report.joinInventory(List.of(other)));
        assertCode("pitInventoryDuplicate", () -> report.joinInventory(List.of(identity, identity)));
        assertCode("pitProofUnexpected", () -> report.normalize(
                Map.of(identity, candidate(1)), Map.of(other, proof(ExecutionStatus.ASSERTION_FAILURE))));
        assertCode("pitInventoryMismatch", () -> report.normalize(Map.of(), Map.of()));
        assertCode("pitInventoryMissing", () -> report.joinInventory(null));
    }

    @Test
    void rejectsReusedSentinelIdentityAndMismatchedSourceLocation() {
        PitReport report = parse(report(mutation("SURVIVED", false)
                + mutation("SURVIVED", false).replace("<index>4</index>", "<index>5</index>")));
        assertCode("pitCandidateDuplicate", () -> report.normalize(Map.of(
                report.results().get(0).identity(), candidate(1),
                report.results().get(1).identity(), candidate(1)), Map.of()));
        var wrong = new MutationCandidate("1".repeat(64), "src/Other.java", "a".repeat(64),
                6, "changed boundary", 1);
        PitReport single = parse(report(mutation("SURVIVED", false)));
        assertCode("pitCandidateMismatch", () -> single.normalize(
                Map.of(single.results().get(0).identity(), wrong), Map.of()));
    }

    @Test
    void emptyReportDoesNotBecomeSuccessfulEvidence() {
        PitReport report = parse(report(""));
        assertEquals(List.of(), report.joinInventory(List.of()));
        assertEquals(false, MutationGate.component(report.normalize(Map.of(), Map.of())).get("pass"));
    }

    @Test
    void rejectsDuplicateBytecodeIdentityEvenIfDisplayMetadataChanges() {
        String first = mutation("KILLED", true);
        invalid(report(first + first.replace("<lineNumber>5", "<lineNumber>6")));
        invalid(report(first + first.replace("Value.java", "Other.java")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"detected='false'", "detected='TRUE'", "detected='1'",
            "status='UNKNOWN'", "numberOfTestsRun='-1'", "numberOfTestsRun=' 1'",
            "numberOfTestsRun='2147483648'"})
    void rejectsInvalidOrInconsistentAttributes(String replacement) {
        String key = replacement.substring(0, replacement.indexOf('='));
        invalid(report(mutation("KILLED", true).replaceAll(key + "='[^']*'", replacement)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sourceFile", "mutatedClass", "mutatedMethod", "methodDescription",
            "lineNumber", "mutator", "indexes", "blocks", "killingTest", "description"})
    void rejectsMissingAndDuplicateFields(String field) {
        String original = mutation("KILLED", true);
        invalid(report(original.replaceAll("<" + field + ">.*?</" + field + ">", "")));
        invalid(report(original.replace("</mutation>", "<" + field + "/>" + "</mutation>")));
    }

    @ParameterizedTest
    @CsvSource({"sourceFile,../Value.java", "sourceFile,/Value.java", "mutatedClass,example/Value",
            "mutatedMethod,bad/name", "methodDescription,not-a-descriptor", "lineNumber,0",
            "index,-1", "index,1.0", "block,-1"})
    void rejectsMalformedFieldValues(String field, String replacement) {
        invalid(report(mutation("KILLED", true)
                .replaceAll("<" + field + ">.*?</" + field + ">",
                        "<" + field + ">" + replacement + "</" + field + ">")));
    }

    @Test
    void rejectsUnknownStructureNestedMarkupAndMalformedDocuments() {
        String valid = report(mutation("KILLED", true));
        invalid(valid.replace("<mutations", "<other").replace("</mutations>", "</other>"));
        invalid(valid.replace("partial=\"true\"", "partial=\"yes\""));
        invalid(valid.replace("partial=\"true\"", ""));
        invalid(valid.replace("<mutations", "<mutations unexpected=\"x\""));
        invalid(valid.replace("<mutation ", "<mutation extra='x' "));
        invalid(valid.replace("Value.java", "<nested>Value.java</nested>"));
        invalid(valid.replace("</mutation>", "<unexpected/></mutation>"));
        invalid(valid.replace("</mutations>", "trailing</mutations>"));
        invalid(valid.replace("</mutations>", ""));
        invalid(valid.replace("<index>4</index>", ""));
        invalid(valid.replace("<index>4</index>", "<index>4</index><index>4</index>"));
        invalid(valid.replace("<block>0</block>", "<index>0</index>"));
    }

    @Test
    void deniesDtdExternalEntitiesXincludeAndOversizedInputs() {
        String valid = report(mutation("KILLED", true));
        invalid("<!DOCTYPE mutations SYSTEM 'http://127.0.0.1:9/private'>" + valid);
        invalid("<!DOCTYPE mutations [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]>"
                + valid.replace("Value.java", "&secret;"));
        invalid(valid.replace("</mutations>",
                "<xi:include xmlns:xi='http://www.w3.org/2001/XInclude' href='file:///etc/passwd'/>"
                        + "</mutations>"));
        assertCode("pitXmlSizeInvalid", () -> PitReportAdapter.parse(new byte[8 * 1024 * 1024 + 1]));
        assertCode("pitXmlSizeInvalid", () -> PitReportAdapter.parse(new byte[0]));
        assertCode("pitXmlSizeInvalid", () -> PitReportAdapter.parse(null));
    }

    @Test
    void exposesImmutableListsAndPreservesMultipleInstructionIndexes() {
        PitReport report = parse(report(mutation("SURVIVED", false)
                .replace("<index>4</index>", "<index>4</index><index>8</index>")));
        assertEquals(List.of(4, 8), report.results().get(0).identity().indexes());
        assertThrows(UnsupportedOperationException.class, () -> report.results().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.results().get(0).blocks().clear());
        List<Integer> indexes = new ArrayList<>(List.of(4));
        var identity = new PitReport.Identity("example.Value", "positive", "(I)Z",
                "example.BoundaryMutator", indexes);
        indexes.add(8);
        assertEquals(List.of(4), identity.indexes());
    }

    private static PitReport parse(String xml) {
        return PitReportAdapter.parse(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static void invalid(String xml) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> parse(xml));
        assertTrue(error.getMessage().startsWith("pit"), error.getMessage());
        assertFalse(error.getMessage().contains("Value.java"));
    }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(IllegalArgumentException.class, action).getMessage());
    }

    private static String report(String mutations) {
        return "<mutations partial=\"true\">" + mutations + "</mutations>";
    }

    private static String mutation(String status, boolean detected) {
        return "<mutation detected='" + detected + "' status='" + status + "' numberOfTestsRun='1'>"
                + "<sourceFile>Value.java</sourceFile><mutatedClass>example.Value</mutatedClass>"
                + "<mutatedMethod>positive</mutatedMethod><methodDescription>(I)Z</methodDescription>"
                + "<lineNumber>5</lineNumber><mutator>example.BoundaryMutator</mutator>"
                + "<indexes><index>4</index></indexes><blocks><block>0</block></blocks>"
                + "<killingTest>example.ValueTest#boundary</killingTest>"
                + "<description>changed boundary</description></mutation>";
    }

    private static MutationCandidate candidate(int ordinal) {
        return new MutationCandidate(Integer.toString(ordinal).repeat(64), "src/example/Value.java",
                "a".repeat(64), 5, "changed boundary", ordinal);
    }

    private static MutationProof proof(ExecutionStatus status) {
        return new MutationProof(execution(ExecutionStatus.PASSED, "a"),
                execution(ExecutionStatus.PASSED, "b"), execution(status, "c"), execution(status, "d"));
    }

    private static TestExecution execution(ExecutionStatus status, String nonce) {
        return new TestExecution(status, "inventory", "test", "java.lang.AssertionError",
                "same assertion", nonce, false, false);
    }
}
