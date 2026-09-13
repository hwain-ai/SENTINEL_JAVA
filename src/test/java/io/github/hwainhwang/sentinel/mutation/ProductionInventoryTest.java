package io.github.hwainhwang.sentinel.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductionInventoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsEveryApprovedJavaSourceInCanonicalOrder() throws Exception {
        Path first = source("src/main/java/demo/First.java", "class First {}\n");
        Path last = source("src/main/java/demo/Last.java", "class Last {}\n");
        Path inventory = temporaryDirectory.resolve("production.inventory");
        Files.writeString(
                inventory,
                "sentinel-java-production-v1\n"
                        + "src/main/java/demo/First.java\n"
                        + "src/main/java/demo/Last.java\n",
                StandardCharsets.UTF_8);

        List<ProductionSource> sources = ProductionInventory.load(temporaryDirectory, inventory);

        assertEquals(List.of(
                temporaryDirectory.relativize(first).toString(),
                temporaryDirectory.relativize(last).toString()),
                sources.stream().map(ProductionSource::relativePath).toList());
        assertEquals(64, sources.get(0).sha256().length());
    }

    @Test
    void rejectsAnInventoryThatOmitsAnExistingProductionJavaSource() throws Exception {
        source("src/main/java/demo/First.java", "class First {}\n");
        source("src/main/java/demo/Omitted.java", "class Omitted {}\n");
        Path inventory = temporaryDirectory.resolve("production.inventory");
        Files.writeString(
                inventory,
                "sentinel-java-production-v1\nsrc/main/java/demo/First.java\n",
                StandardCharsets.UTF_8);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ProductionInventory.load(temporaryDirectory, inventory));

        assertEquals("productionInventoryIncomplete", failure.getMessage());
    }

    @Test
    void rejectsUnsortedTraversalAndSymbolicLinkEntries() throws Exception {
        source("src/main/java/demo/First.java", "class First {}\n");
        Path inventory = temporaryDirectory.resolve("production.inventory");
        Files.writeString(
                inventory,
                "sentinel-java-production-v1\n../outside.java\n",
                StandardCharsets.UTF_8);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ProductionInventory.load(temporaryDirectory, inventory));

        assertEquals("productionInventoryPathInvalid", failure.getMessage());
    }

    @Test
    void checkedSelfInventoryNamesEveryCurrentProductionSource() throws Exception {
        Path repository = Path.of("").toAbsolutePath().normalize();

        List<ProductionSource> sources = ProductionInventory.load(
                repository, repository.resolve("production.inventory"));

        assertEquals(62, sources.size());
    }

    private Path source(String relative, String body) throws Exception {
        Path file = temporaryDirectory.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return file;
    }
}
