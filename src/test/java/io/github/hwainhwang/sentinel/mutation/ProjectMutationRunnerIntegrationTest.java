package io.github.hwainhwang.sentinel.mutation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("no-mutate")
class ProjectMutationRunnerIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void runsThePinnedBackendAcrossTheApprovedInventoryWithTypedJunitReplays()
            throws Exception {
        Path toolRoot = Path.of("").toAbsolutePath().normalize();
        Path project = syntheticProject();
        Path firstSource = project.resolve("src/main/java/demo/FirstFlag.java");
        Path lastSource = project.resolve("src/main/java/demo/LastFlag.java");
        byte[] firstOriginal = Files.readAllBytes(firstSource);
        byte[] lastOriginal = Files.readAllBytes(lastSource);
        Path inventory = project.resolve("production.inventory");
        Files.writeString(
                inventory,
                "sentinel-java-production-v1\n"
                        + "src/main/java/demo/FirstFlag.java\n"
                        + "src/main/java/demo/LastFlag.java\n",
                StandardCharsets.UTF_8);
        ProjectMutationRequest request = new ProjectMutationRequest(
                project,
                inventory,
                toolRoot.resolve(".toolchain/backends/mutate4java-7b05fdd.jar"),
                LockedToolchain.javaHome(toolRoot),
                LockedToolchain.mavenHome(toolRoot),
                toolRoot.resolve(".toolchain/m2"),
                toolRoot.resolve("target/classes"),
                120_000L);

        MutationRun run = new ProjectMutationRunner().run(request);

        assertEquals(2, run.records().size());
        assertTrue(run.records().stream()
                .allMatch(record -> record.state() == MutationState.KILLED));
        assertTrue((Boolean) run.evidenceComponent().get("pass"));
        assertArrayEquals(firstOriginal, Files.readAllBytes(firstSource));
        assertArrayEquals(lastOriginal, Files.readAllBytes(lastSource));
        assertFalse(Files.readString(firstSource).contains("mutate4java-manifest"));
        assertFalse(Files.readString(lastSource).contains("mutate4java-manifest"));
    }

    @Test
    void joinsTwoSameSourceBackendCandidatesToTheirAuthenticatedJunitExecutions()
            throws Exception {
        Path toolRoot = Path.of("").toAbsolutePath().normalize();
        Path project = syntheticTwoMutantProject();
        Path source = project.resolve("src/main/java/demo/FirstFlag.java");
        byte[] original = Files.readAllBytes(source);
        Path inventory = project.resolve("production.inventory");
        Files.writeString(
                inventory,
                "sentinel-java-production-v1\n"
                        + "src/main/java/demo/FirstFlag.java\n",
                StandardCharsets.UTF_8);
        ProjectMutationRequest request = new ProjectMutationRequest(
                project,
                inventory,
                toolRoot.resolve(".toolchain/backends/mutate4java-7b05fdd.jar"),
                LockedToolchain.javaHome(toolRoot),
                LockedToolchain.mavenHome(toolRoot),
                toolRoot.resolve(".toolchain/m2"),
                toolRoot.resolve("target/classes"),
                120_000L);

        MutationRun run = new ProjectMutationRunner().run(request);

        assertEquals(2, run.records().size());
        assertEquals(
                java.util.List.of(1, 2),
                run.records().stream().map(record -> record.candidate().ordinal()).toList());
        assertTrue(run.records().stream()
                .allMatch(record -> record.state() == MutationState.KILLED));
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test
    void rejectsAReplayThatChangesAnotherInventoriedSourceInTheSnapshot()
            throws Exception {
        Path toolRoot = Path.of("").toAbsolutePath().normalize();
        Path project = syntheticProjectWhoseTestsEditAnotherSource();
        Path lastSource = project.resolve("src/main/java/demo/LastFlag.java");
        byte[] lastOriginal = Files.readAllBytes(lastSource);
        Path inventory = project.resolve("production.inventory");
        Files.writeString(
                inventory,
                "sentinel-java-production-v1\n"
                        + "src/main/java/demo/FirstFlag.java\n"
                        + "src/main/java/demo/LastFlag.java\n",
                StandardCharsets.UTF_8);
        ProjectMutationRequest request = new ProjectMutationRequest(
                project,
                inventory,
                toolRoot.resolve(".toolchain/backends/mutate4java-7b05fdd.jar"),
                LockedToolchain.javaHome(toolRoot),
                LockedToolchain.mavenHome(toolRoot),
                toolRoot.resolve(".toolchain/m2"),
                toolRoot.resolve("target/classes"),
                120_000L);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new ProjectMutationRunner().run(request));

        assertEquals("snapshotSourceIdentityChanged", failure.getMessage());
        assertArrayEquals(lastOriginal, Files.readAllBytes(lastSource));
    }

    @Test
    void rejectsAMutantTestThatChangesAnotherSourceInTheBackendWorker()
            throws Exception {
        Path toolRoot = Path.of("").toAbsolutePath().normalize();
        Path project = syntheticProjectWhoseMutantTestEditsAnotherSource();
        Path lastSource = project.resolve("src/main/java/demo/LastFlag.java");
        byte[] lastOriginal = Files.readAllBytes(lastSource);
        Path inventory = project.resolve("production.inventory");
        Files.writeString(
                inventory,
                "sentinel-java-production-v1\n"
                        + "src/main/java/demo/FirstFlag.java\n"
                        + "src/main/java/demo/LastFlag.java\n",
                StandardCharsets.UTF_8);
        ProjectMutationRequest request = new ProjectMutationRequest(
                project,
                inventory,
                toolRoot.resolve(".toolchain/backends/mutate4java-7b05fdd.jar"),
                LockedToolchain.javaHome(toolRoot),
                LockedToolchain.mavenHome(toolRoot),
                toolRoot.resolve(".toolchain/m2"),
                toolRoot.resolve("target/classes"),
                120_000L);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new ProjectMutationRunner().run(request));

        assertEquals("mutationWorkerSourceIdentityChanged", failure.getMessage());
        assertArrayEquals(lastOriginal, Files.readAllBytes(lastSource));
    }

    private Path syntheticProject() throws Exception {
        Path project = temporaryDirectory.resolve("project");
        write(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>fixture</groupId>
                  <artifactId>typed-mutation-fixture</artifactId>
                  <version>1</version>
                  <properties>
                    <maven.compiler.release>17</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <version>5.10.2</version>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                  <build><plugins>
                    <plugin>
                      <groupId>org.apache.maven.plugins</groupId>
                      <artifactId>maven-compiler-plugin</artifactId>
                      <version>3.13.0</version>
                    </plugin>
                    <plugin>
                      <groupId>org.apache.maven.plugins</groupId>
                      <artifactId>maven-surefire-plugin</artifactId>
                      <version>3.2.5</version>
                    </plugin>
                  </plugins></build>
                </project>
                """);
        write(project.resolve("src/main/java/demo/FirstFlag.java"), """
                package demo;
                public final class FirstFlag {
                    public boolean enabled() {
                        return true;
                    }
                }
                """);
        write(project.resolve("src/main/java/demo/LastFlag.java"), """
                package demo;
                public final class LastFlag {
                    public boolean enabled() {
                        return true;
                    }
                }
                """);
        write(project.resolve("src/test/java/demo/FlagTest.java"), """
                package demo;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                import org.junit.jupiter.api.Test;
                final class FlagTest {
                    @Test
                    void enabledByDefault() {
                        assertTrue(new FirstFlag().enabled());
                        assertTrue(new LastFlag().enabled());
                    }
                }
                """);
        return project;
    }

    private Path syntheticProjectWhoseTestsEditAnotherSource() throws Exception {
        Path project = syntheticProject();
        write(project.resolve("src/main/java/demo/LastFlag.java"), """
                package demo;
                public final class LastFlag {}
                """);
        write(project.resolve("src/test/java/demo/FlagTest.java"), """
                package demo;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import org.junit.jupiter.api.Test;
                final class FlagTest {
                    @Test
                    void enabledByDefault() throws Exception {
                        Files.writeString(
                                Path.of("src/main/java/demo/LastFlag.java"),
                                "package demo; public final class LastFlag { int changed; }\\n");
                        assertTrue(new FirstFlag().enabled());
                    }
                }
                """);
        return project;
    }

    private Path syntheticTwoMutantProject() throws Exception {
        Path project = syntheticProject();
        Files.delete(project.resolve("src/main/java/demo/LastFlag.java"));
        write(project.resolve("src/main/java/demo/FirstFlag.java"), """
                package demo;
                public final class FirstFlag {
                    public boolean firstEnabled() {
                        return true;
                    }
                    public boolean secondEnabled() {
                        return true;
                    }
                }
                """);
        write(project.resolve("src/test/java/demo/FlagTest.java"), """
                package demo;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                import org.junit.jupiter.api.Test;
                final class FlagTest {
                    @Test
                    void bothFlagsAreEnabled() {
                        FirstFlag flag = new FirstFlag();
                        assertTrue(flag.firstEnabled());
                        assertTrue(flag.secondEnabled());
                    }
                }
                """);
        return project;
    }

    private Path syntheticProjectWhoseMutantTestEditsAnotherSource() throws Exception {
        Path project = syntheticProject();
        write(project.resolve("src/main/java/demo/LastFlag.java"), """
                package demo;
                public final class LastFlag {}
                """);
        write(project.resolve("src/test/java/demo/FlagTest.java"), """
                package demo;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import org.junit.jupiter.api.Test;
                final class FlagTest {
                    @Test
                    void enabledByDefault() throws Exception {
                        FirstFlag flag = new FirstFlag();
                        if (!flag.enabled()) {
                            Files.writeString(
                                    Path.of("src/main/java/demo/LastFlag.java"),
                                    "package demo; public final class LastFlag { int changed; }\\n");
                        }
                        assertTrue(flag.enabled());
                    }
                }
                """);
        return project;
    }

    private static void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value, StandardCharsets.UTF_8);
    }
}
