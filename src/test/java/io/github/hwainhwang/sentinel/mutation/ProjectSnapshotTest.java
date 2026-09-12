package io.github.hwainhwang.sentinel.mutation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSnapshotTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void excludesGeneratedDirectoriesAtEveryModuleDepth() throws Exception {
        Path source = temporaryDirectory.resolve("module/src/main/java/demo/Flag.java");
        Path stale = temporaryDirectory.resolve("module/target/stale.class");
        Files.createDirectories(source.getParent());
        Files.createDirectories(stale.getParent());
        Files.createFile(source);
        Files.createFile(stale);

        try (ProjectSnapshot snapshot = ProjectSnapshot.create(temporaryDirectory)) {
            assertTrue(Files.exists(snapshot.root().resolve(
                    "module/src/main/java/demo/Flag.java")));
            assertFalse(Files.exists(snapshot.root().resolve("module/target")));
        }
    }

    @Test
    void rejectsMavenArgumentOverridesInsteadOfSilentlyApplyingThem() throws Exception {
        Path override = temporaryDirectory.resolve(".mvn/maven.config");
        Files.createDirectories(override.getParent());
        Files.createFile(override);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ProjectSnapshot.create(temporaryDirectory));

        assertEquals("snapshotMavenOverrideInvalid", failure.getMessage());
    }
}
