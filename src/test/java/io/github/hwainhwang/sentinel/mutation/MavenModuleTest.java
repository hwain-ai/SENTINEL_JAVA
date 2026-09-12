package io.github.hwainhwang.sentinel.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenModuleTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesTheNearestOwningPomAndModuleRelativeSource() throws Exception {
        Files.createFile(temporaryDirectory.resolve("pom.xml"));
        Path source = temporaryDirectory.resolve("module-a/src/main/java/demo/Flag.java");
        Files.createDirectories(source.getParent());
        Files.createFile(temporaryDirectory.resolve("module-a/pom.xml"));
        Files.createFile(source);

        MavenModule module = MavenModule.locate(
                temporaryDirectory, "module-a/src/main/java/demo/Flag.java");

        assertEquals(temporaryDirectory.resolve("module-a"), module.root());
        assertEquals("src/main/java/demo/Flag.java", module.relativeSource());
    }
}
