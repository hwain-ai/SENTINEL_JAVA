package io.github.hwainhwang.sentinel.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PitProbeReplayTest {
    @TempDir Path root;

    @Test
    void bindsRealPitCandidatesToFreshControlsAndMatchingAssertionReplays() throws Exception {
        Path project = PitProbeTest.fixture(root.resolve("strong"),
                "assertTrue(new Value().positive(1));\n"
                + "assertFalse(new Value().positive(0));\n"
                + "assertFalse(new Value().positive(-1));\n");
        var original = PitProbeTest.request(project);
        var request = new PitProbe.Request(project, original.artifactRoot(), original.targetClasses(),
                original.testClasses(), 120_000);
        Map<String, Object> report = PitProbe.run(request);
        assertTrue(report.containsKey("replays"), "PIT results have no candidate-bound replay evidence");
        assertEquals(false, report.get("certified"));
        List<?> replays = (List<?>) report.get("replays");
        assertEquals(3, replays.size());
        var nonces = new HashSet<String>();
        var expectedIds = new HashSet<String>();
        for (Object value : (List<?>) report.get("mutants")) {
            expectedIds.add((String) ((Map<?, ?>) value).get("id"));
        }
        var actualIds = new HashSet<String>();
        for (Object value : replays) {
            Map<?, ?> replay = (Map<?, ?>) value;
            assertTrue(actualIds.add((String) replay.get("candidateId")));
            assertEquals("KILLED", replay.get("status"));
            Map<?, ?> first = (Map<?, ?>) replay.get("mutantFirst");
            Map<?, ?> second = (Map<?, ?>) replay.get("mutantSecond");
            Map<?, ?> control = (Map<?, ?>) replay.get("controlFirst");
            assertEquals(first.get("inputSha256"), second.get("inputSha256"));
            assertEquals(first.get("failureSignature"), second.get("failureSignature"));
            assertEquals(first.get("resultsSha256"), second.get("resultsSha256"));
            assertNotEquals(first.get("inputSha256"), control.get("inputSha256"));
            assertEquals(first.get("originalClassesSha256"), control.get("originalClassesSha256"));
            assertFalse(((String) first.get("failureSignature")).isEmpty());
            for (String phase : List.of("controlFirst", "controlSecond", "mutantFirst", "mutantSecond")) {
                Map<?, ?> execution = (Map<?, ?>) replay.get(phase);
                assertTrue(nonces.add((String) execution.get("nonce")));
                assertTrue(((String) execution.get("inputSha256")).matches("[0-9a-f]{64}"));
                assertEquals(execution.get("bindingSha256"), MutationHash.digest("sentinel-java-pit-execution-binding-v1",
                        List.of((String) report.get("planSha256"), (String) replay.get("candidateId"),
                                phase.startsWith("control") ? "control" : "mutant", (String) execution.get("inputSha256"),
                                (String) execution.get("originalClassesSha256"), (String) execution.get("mutantClassSha256"))));
            }
        }
        assertEquals(expectedIds, actualIds);
    }

    @Test
    void doesNotDeleteOutsideFilesWhenTestsReplaceTheRequestParent() throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path canary = outside.resolve("sentinel-junit-request-v1");
        Files.writeString(canary, "preserve outside content");
        String body = "if (java.nio.file.Files.exists(java.nio.file.Path.of(\"target/sentinel-junit-request-v1\"))) {\n"
                + "java.nio.file.Files.move(java.nio.file.Path.of(\"target\"), java.nio.file.Path.of(\"moved\"));\n"
                + "java.nio.file.Files.createSymbolicLink(java.nio.file.Path.of(\"target\"), java.nio.file.Path.of(\""
                + outside + "\"));\n}";
        Path project = PitProbeTest.fixture(root.resolve("parent-alias"), body);
        assertThrows(Exception.class, () -> PitProbe.run(PitProbeTest.request(project)));
        assertTrue(Files.exists(canary), "replay request cleanup deleted an outside file");
        assertEquals("preserve outside content", Files.readString(canary));
    }

    @Test
    void keepsBackendKillsFromRuntimeFailuresOutOfAssertionKills() throws Exception {
        Path project = PitProbeTest.fixture(root.resolve("runtime"),
                "if (!new Value().positive(1) || new Value().positive(0) || new Value().positive(-1)) {\n"
                + "throw new IllegalStateException(\"private runtime detail\");\n}");
        Map<String, Object> report = PitProbe.run(PitProbeTest.request(project));
        assertEquals(3, ((Map<?, ?>) report.get("backendCounts")).get("KILLED"));
        for (Object value : (List<?>) report.get("replays")) {
            assertEquals("RUNTIME_ERROR", ((Map<?, ?>) value).get("status"));
        }
    }

    @Test
    void refusesEqualMessagesFromAlternatingFailureSitesDuringActualPitReplays() throws Exception {
        Path counter = root.resolve("counter");
        String body = "if (!new Value().positive(1) || new Value().positive(0) || new Value().positive(-1)) {\n"
                + "var path = java.nio.file.Path.of(\"" + counter + "\");\n"
                + "int count = java.nio.file.Files.exists(path) ? Integer.parseInt(java.nio.file.Files.readString(path)) : 0;\n"
                + "java.nio.file.Files.writeString(path, Integer.toString(count + 1));\n"
                + "if (count % 2 == 0) { assertEquals(1, 2); }\n"
                + "else { assertEquals(1, 2); }\n}";
        Path project = PitProbeTest.fixture(root.resolve("alternating"), body);
        Map<String, Object> report = PitProbe.run(PitProbeTest.request(project));
        assertEquals(3, ((Map<?, ?>) report.get("backendCounts")).get("KILLED"));
        for (Object value : (List<?>) report.get("replays")) {
            assertEquals("TOOL_ERROR", ((Map<?, ?>) value).get("status"));
        }
    }

    @Test
    void rejectsUnexpectedProcessExitAfterAValidReplayEvent() throws Exception {
        Path project = PitProbeTest.fixture(root.resolve("exit"),
                "if (java.nio.file.Files.exists(java.nio.file.Path.of(\"target/sentinel-junit-request-v1\"))) {\n"
                + "Runtime.getRuntime().addShutdownHook(new Thread(() -> Runtime.getRuntime().halt(42)));\n}");
        var failure = assertThrows(IllegalArgumentException.class, () -> PitProbe.run(PitProbeTest.request(project)));
        assertEquals("pitReplayEventMismatch", failure.getMessage());
    }
}
