package io.github.hwainhwang.sentinel.mutation.junit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JUnitPrivateFilesTest {
    @TempDir Path directory;

    @Test
    void writesOnePrivateFileWithoutReplacingExistingContent() throws Exception {
        Path event = directory.resolve("event");
        byte[] content = new byte[] {1, 2, 3};
        JUnitPrivateFiles.write(event, content);
        assertArrayEquals(content, Files.readAllBytes(event));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(event));
        assertThrows(IOException.class, () -> JUnitPrivateFiles.write(event, new byte[] {4}));
        assertArrayEquals(content, Files.readAllBytes(event));
    }

    @Test
    void rejectsLinksInAncestorsAndAtTheDestination() throws Exception {
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path ancestor = directory.resolve("alias");
        Files.createSymbolicLink(ancestor, outside);
        assertThrows(IOException.class, () -> JUnitPrivateFiles.write(ancestor.resolve("event"), new byte[] {1}));
        assertFalse(Files.exists(outside.resolve("event")));
        Path event = directory.resolve("event");
        Files.createSymbolicLink(event, outside.resolve("event"));
        assertThrows(IOException.class, () -> JUnitPrivateFiles.write(event, new byte[] {1}));
        assertFalse(Files.exists(outside.resolve("event")));
    }

    @Test
    void pathRouteWritesOncePrivatelyAndRefusesLinksAndReplacement() throws Exception {
        Path event = directory.resolve("event");
        byte[] content = new byte[] {1, 2, 3};
        JUnitPrivateFiles.writeByPath(event, content);
        assertArrayEquals(content, Files.readAllBytes(event));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(event));
        assertThrows(IOException.class, () -> JUnitPrivateFiles.writeByPath(event, new byte[] {4}));
        assertArrayEquals(content, Files.readAllBytes(event));

        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path ancestor = directory.resolve("alias");
        Files.createSymbolicLink(ancestor, outside);
        assertThrows(IOException.class, () -> JUnitPrivateFiles.writeByPath(ancestor.resolve("event"), new byte[] {1}));
        Path linked = directory.resolve("linked");
        Files.createSymbolicLink(linked, outside.resolve("linked"));
        assertThrows(IOException.class, () -> JUnitPrivateFiles.writeByPath(linked, new byte[] {1}));
        assertFalse(Files.exists(outside.resolve("event")));
        assertFalse(Files.exists(outside.resolve("linked")));
        assertThrows(IllegalArgumentException.class, () -> JUnitPrivateFiles.writeByPath(Path.of("event"), new byte[] {1}));
        assertThrows(IOException.class, () -> JUnitPrivateFiles.writeByPath(directory.resolve("missing/event"), new byte[] {1}));
    }

    @Test
    void rejectsRelativeUnnormalizedRootAndMissingParents() {
        for (Path path : List.of(Path.of("event"), directory.resolve("../event"), Path.of("/"))) {
            assertThrows(IllegalArgumentException.class, () -> JUnitPrivateFiles.write(path, new byte[] {1}));
        }
        assertThrows(IOException.class, () -> JUnitPrivateFiles.write(directory.resolve("missing/event"), new byte[] {1}));
    }
}
