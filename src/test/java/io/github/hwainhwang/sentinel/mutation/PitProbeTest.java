package io.github.hwainhwang.sentinel.mutation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hwainhwang.sentinel.evidence.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PitProbeTest {
    @TempDir Path root;

    @Test
    void exposesTheOptInExecutionEntryPoint() {
        assertDoesNotThrow(() -> Class.forName("io.github.hwainhwang.sentinel.mutation.PitProbe"));
    }

    @Test
    void runsPinnedPitStrongAndWeakCasesWithIndependentDiscoveryAndNoCertification() throws Exception {
        String previousPlan = null;
        for (boolean strong : List.of(true, false)) {
            Path project = fixture(root.resolve(strong ? "strong" : "weak"),
                    "assertTrue(new Value().positive(1));"
                            + (strong ? "assertFalse(new Value().positive(0)); assertFalse(new Value().positive(-1));" : ""));
            byte[] original = Files.readAllBytes(project.resolve("src/main/java/example/Value.java"));
            Map<String, Object> report = PitProbe.run(request(project));
            assertEquals(false, report.get("certified"));
            assertEquals(true, report.get("discoveryVerified"));
            assertEquals("1.30.0", report.get("version"));
            assertEquals(3, ((List<?>) report.get("mutants")).size());
            String plan = (String) report.get("planSha256");
            assertTrue(plan.matches("[0-9a-f]{64}"));
            assertFalse(plan.equals(previousPlan));
            previousPlan = plan;
            for (Object mutant : (List<?>) report.get("mutants")) {
                assertEquals(PitProbeFiles.sha256(original), ((Map<?, ?>) mutant).get("sourceSha256"));
            }
            Map<?, ?> counts = (Map<?, ?>) report.get("backendCounts");
            assertEquals(strong ? 3 : 1, counts.get("KILLED"));
            assertEquals(strong ? 0 : 2, counts.get("SURVIVED"));
            List<?> replays = (List<?>) report.get("replays");
            assertEquals(strong ? 3 : 1, replays.stream().filter(value -> "KILLED".equals(((Map<?, ?>) value).get("status"))).count());
            assertEquals(strong ? 0 : 2, replays.stream().filter(value -> "SURVIVED".equals(((Map<?, ?>) value).get("status"))).count());
            assertEquals(new String(original, StandardCharsets.UTF_8),
                    Files.readString(project.resolve("src/main/java/example/Value.java")));
            assertFalse(Files.exists(project.resolve("target")));
            String output = new String(CanonicalJson.file(report), StandardCharsets.UTF_8);
            assertFalse(output.contains(project.toString()));
            assertFalse(output.contains("return value"));
            assertFalse(output.contains("killingTest"));
        }
    }

    @Test
    void refusesFailingBaselineAndCompileFailure() throws Exception {
        Path project = fixture(root.resolve("baseline"), "fail(\"private detail\");");
        assertCode("pitProbeBaselineFailed", () -> PitProbe.run(request(project)));
        Files.writeString(project.resolve("src/main/java/example/Value.java"), "package broken !!!");
        assertCode("pitProbeCompileFailed", () -> PitProbe.run(request(project)));
    }

    @Test
    void boundsExecutionAndPreservesOriginalWhenTestDoesNotFinish() throws Exception {
        Path project = fixture(root.resolve("timeout"), "while (true) { Thread.onSpinWait(); }");
        var request = request(project);
        var limited = new PitProbe.Request(project, request.artifactRoot(), request.targetClasses(),
                request.testClasses(), 1800);
        long started = System.nanoTime();
        assertCode("pitProbeTimedOut", () -> PitProbe.run(limited));
        assertTrue((System.nanoTime() - started) / 1_000_000 < 8000);
        assertFalse(Files.exists(project.resolve("target")));
    }

    @Test
    void refusesTestSideSourceChanges() throws Exception {
        Path project = fixture(root.resolve("tamper"),
                "java.nio.file.Files.writeString(java.nio.file.Path.of(\"src/main/java/example/Value.java\"), \"changed\");");
        assertCode("pitProbeSourceChanged", () -> PitProbe.run(request(project)));
        assertTrue(Files.readString(project.resolve("src/main/java/example/Value.java")).contains("return value"));
    }

    @Test
    void refusesTestSideCompiledClassChanges() throws Exception {
        Path project = fixture(root.resolve("class-tamper"),
                "java.nio.file.Files.write(java.nio.file.Path.of(\"target/classes/example/Value.class\"), new byte[] {1});");
        assertCode("pitProbeClassesChanged", () -> PitProbe.run(request(project)));
    }

    @Test
    void refusesMissingExactTargetInsteadOfReturningAnEmptySuccess() throws Exception {
        Path project = fixture(root.resolve("missing-target"), "");
        var valid = request(project);
        var missing = new PitProbe.Request(project, valid.artifactRoot(), List.of("example.Absent"),
                valid.testClasses(), 30_000);
        assertCode("pitProbeClassMissing", () -> PitProbe.run(missing));
    }

    @Test
    void rejectsCorruptedAndLinkedPinnedArtifacts() throws Exception {
        Path artifacts = root.resolve("pinned");
        Files.createDirectory(artifacts);
        try (var installed = Files.list(request(root).artifactRoot())) {
            for (Path file : installed.toList()) {
                Files.copy(file, artifacts.resolve(file.getFileName()));
            }
        }
        Path core = artifacts.resolve("pitest-1.30.0.jar");
        byte[] content = Files.readAllBytes(core);
        content[0] ^= 1;
        Files.write(core, content);
        assertCode("pitProbeArtifactsInvalid", () -> PitProbeArtifacts.verify(artifacts));
        Files.delete(core);
        Files.createSymbolicLink(core, request(root).artifactRoot().resolve(core.getFileName()));
        assertCode("pitProbeArtifactsInvalid", () -> PitProbeArtifacts.verify(artifacts));
    }

    @Test
    void refusesArtifactsBeforeStartingTargetCode() throws Exception {
        Path project = fixture(root.resolve("artifacts"), "fail(\"must not run\");");
        var original = request(project);
        var missing = new PitProbe.Request(project, root.resolve("missing"), original.targetClasses(),
                original.testClasses(), 30_000);
        assertCode("pitProbeArtifactsInvalid", () -> PitProbe.run(missing));
    }

    @Test
    void rejectsOversizedAndLinkedSourceInputsBeforeReadingOrCopyingThem() throws Exception {
        Path project = fixture(root.resolve("size"), "");
        Path source = project.resolve("src/main/java/example/Value.java");
        Files.write(source, new byte[4 * 1024 * 1024 + 1]);
        assertCode("pitProbeSourceTooLarge", () -> PitProbe.run(request(project)));
        Files.delete(source);
        Files.createSymbolicLink(source, root.resolve("outside.java"));
        assertCode("pitProbeSourceInvalid", () -> PitProbe.run(request(project)));
    }

    @Test
    void rejectsInvalidClassScopeAndTimeout() throws Exception {
        Path project = fixture(root.resolve("request"), "");
        var valid = request(project);
        for (String name : List.of("*", "example.*", "../Value", "example.Value,Other")) {
            var invalid = new PitProbe.Request(project, valid.artifactRoot(), List.of(name), valid.testClasses(), 1000);
            assertCode("pitProbeRequestInvalid", () -> PitProbe.run(invalid));
        }
        var invalid = new PitProbe.Request(project, valid.artifactRoot(), valid.targetClasses(), valid.testClasses(), 0);
        assertCode("pitProbeRequestInvalid", () -> PitProbe.run(invalid));
    }

    @Test
    void refusesAbsentEmptyDuplicateAndExcessiveClassLists() throws Exception {
        Path project = fixture(root.resolve("class-lists"), "");
        var valid = request(project);
        List<List<String>> cases = new java.util.ArrayList<>();
        cases.add(null);
        cases.add(List.of());
        cases.add(List.of("example.Value", "example.Value"));
        cases.add(java.util.Collections.nCopies(65, "example.Value"));
        cases.add(java.util.Arrays.asList((String) null));
        for (List<String> names : cases) {
            var invalid = new PitProbe.Request(project, valid.artifactRoot(), names, valid.testClasses(), 1000);
            assertCode("pitProbeRequestInvalid", () -> PitProbe.run(invalid));
        }
    }

    @Test
    void rejectsResourcesModuleDescriptorsAndHardlinkedSourcesExplicitly() throws Exception {
        Path project = fixture(root.resolve("unsupported"), "");
        Path resources = Files.createDirectory(project.resolve("src/main/resources"));
        assertCode("pitProbeResourcesUnsupported", () -> PitProbe.run(request(project)));
        Files.delete(resources);
        Path module = project.resolve("src/main/java/module-info.java");
        Files.writeString(module, "module example {}");
        assertCode("pitProbeSourceInvalid", () -> PitProbe.run(request(project)));
        Files.delete(module);
        Files.createLink(root.resolve("linked-source"), project.resolve("src/main/java/example/Value.java"));
        assertCode("pitProbeSourceInvalid", () -> PitProbe.run(request(project)));
    }

    @Test
    void honorsThreadInterruptionBeforeStarting() throws Exception {
        Path project = fixture(root.resolve("cancel"), "");
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> PitProbe.run(request(project)));
        } finally {
            Thread.interrupted();
        }
    }

    static PitProbe.Request request(Path project) {
        return new PitProbe.Request(project, Path.of(".toolchain/pit-probe").toAbsolutePath().normalize(),
                List.of("example.Value"), List.of("example.ValueTest"), 120_000);
    }

    static Path fixture(Path project, String body) throws Exception {
        Path production = project.resolve("src/main/java/example/Value.java");
        Path tests = project.resolve("src/test/java/example/ValueTest.java");
        Files.createDirectories(production.getParent());
        Files.createDirectories(tests.getParent());
        Files.writeString(production,
                "package example;\npublic class Value { public boolean positive(int value) { return value > 0; } }\n");
        Files.writeString(tests, "package example;\nimport org.junit.jupiter.api.Test;\n"
                + "import static org.junit.jupiter.api.Assertions.*;\n"
                + "class ValueTest { @Test void checks() throws Exception { " + body + " } }\n");
        return project;
    }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable executable) {
        assertEquals(code, assertThrows(IllegalArgumentException.class, executable).getMessage());
    }
}
