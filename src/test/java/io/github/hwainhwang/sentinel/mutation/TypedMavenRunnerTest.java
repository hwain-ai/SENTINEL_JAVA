package io.github.hwainhwang.sentinel.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TypedMavenRunnerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void excludesTestsThatCanRecursivelyStartMutation() throws Exception {
        Path toolRoot = Path.of("").toAbsolutePath().normalize();
        Path project = syntheticProject();
        Path events = temporaryDirectory.resolve("events");
        Files.createDirectory(
                events,
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
        ProjectMutationRequest request = new ProjectMutationRequest(
                project,
                project.resolve("production.inventory"),
                toolRoot.resolve(".toolchain/backends/mutate4java-7b05fdd.jar"),
                LockedToolchain.javaHome(toolRoot),
                LockedToolchain.mavenHome(toolRoot),
                toolRoot.resolve(".toolchain/m2"),
                toolRoot.resolve("target/classes"),
                120_000L);
        TypedMavenRunner runner = new TypedMavenRunner(request, events);

        TestExecution execution = runner.run(
                project, "src/main/java/demo/Flag.java", 120_000L);

        assertEquals(ExecutionStatus.PASSED, execution.status());
    }

    @Test
    void rejectsAnAmbiguousGeneratedOutputCacheInsteadOfGuessing() throws Exception {
        Path toolRoot = Path.of("").toAbsolutePath().normalize();
        Path project = syntheticProject();
        Path events = temporaryDirectory.resolve("cache-events");
        Files.createDirectory(
                events,
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
        Files.createSymbolicLink(
                project.resolve("target"), temporaryDirectory.resolve("outside-cache"));
        ProjectMutationRequest request = new ProjectMutationRequest(
                project,
                project.resolve("production.inventory"),
                toolRoot.resolve(".toolchain/backends/mutate4java-7b05fdd.jar"),
                LockedToolchain.javaHome(toolRoot),
                LockedToolchain.mavenHome(toolRoot),
                toolRoot.resolve(".toolchain/m2"),
                toolRoot.resolve("target/classes"),
                120_000L);
        TypedMavenRunner runner = new TypedMavenRunner(request, events);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> runner.run(project, "src/main/java/demo/Flag.java", 120_000L));

        assertEquals("mutationCacheObservationUnknown", failure.getMessage());
    }

    private Path syntheticProject() throws Exception {
        Path project = temporaryDirectory.resolve("project");
        write(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>fixture</groupId>
                  <artifactId>tag-filter-fixture</artifactId>
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
        write(project.resolve("src/main/java/demo/Flag.java"), """
                package demo;
                public final class Flag {}
                """);
        write(project.resolve("src/test/java/demo/IncludedTest.java"), """
                package demo;
                import org.junit.jupiter.api.Test;
                final class IncludedTest {
                    @Test
                    void passes() {}
                }
                """);
        write(project.resolve("src/test/java/demo/RecursiveTest.java"), """
                package demo;
                import static org.junit.jupiter.api.Assertions.fail;
                import org.junit.jupiter.api.Tag;
                import org.junit.jupiter.api.Test;
                @Tag("no-mutate")
                final class RecursiveTest {
                    @Test
                    void mustNotRunInsideMutation() {
                        fail("recursive mutation test ran");
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
