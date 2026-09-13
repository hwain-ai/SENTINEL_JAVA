package io.github.hwainhwang.sentinel.mutation.junit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Exclusive event creation without following any path component: through directory handles where the
 * JDK offers them (Linux), otherwise by checking every component right before a non-following open.
 */
final class JUnitPrivateFiles {
    private JUnitPrivateFiles() {
        throw new AssertionError("no instances");
    }

    static void write(Path file, byte[] bytes) throws IOException {
        validate(file);
        try (var directory = Files.newDirectoryStream(file.getRoot())) {
            if (directory instanceof SecureDirectoryStream<Path> secure) {
                write(secure, file.getRoot().relativize(file), ByteBuffer.wrap(bytes));
                return;
            }
        }
        writeByPath(file, bytes);
    }

    /**
     * The route for file systems without SecureDirectoryStream (JDK 17 on macOS). Package-private so the
     * Linux test suite covers it too.
     */
    static void writeByPath(Path file, byte[] bytes) throws IOException {
        validate(file);
        // RISK(security): only a swap between these checks and the open goes unnoticed on this route.
        for (Path ancestor = file.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.isSymbolicLink(ancestor) || !Files.isDirectory(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("junitEventDirectoryInvalid");
            }
        }
        try (var output = Files.newByteChannel(file,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            ByteBuffer remaining = ByteBuffer.wrap(bytes);
            while (remaining.hasRemaining()) {
                output.write(remaining);
            }
        }
    }

    private static void validate(Path file) {
        if (!file.isAbsolute() || !file.normalize().equals(file) || file.getParent() == null) {
            throw new IllegalArgumentException("junitEventPathInvalid");
        }
    }

    // RISK(security): descriptor-relative traversal blocks symlink swaps, not arbitrary same-user code.
    private static void write(SecureDirectoryStream<Path> directory, Path relative, ByteBuffer bytes)
            throws IOException {
        if (relative.getNameCount() > 1) {
            try (var next = directory.newDirectoryStream(relative.getName(0), LinkOption.NOFOLLOW_LINKS)) {
                write(next, relative.subpath(1, relative.getNameCount()), bytes);
            }
            return;
        }
        try (var output = directory.newByteChannel(relative,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            while (bytes.hasRemaining()) {
                output.write(bytes);
            }
        }
    }
}
