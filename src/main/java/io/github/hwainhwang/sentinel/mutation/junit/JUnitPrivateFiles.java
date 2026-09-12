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

/** Exclusive event creation through directory handles, without following any path component. */
final class JUnitPrivateFiles {
    private JUnitPrivateFiles() {
        throw new AssertionError("no instances");
    }

    static void write(Path file, byte[] bytes) throws IOException {
        if (!file.isAbsolute() || !file.normalize().equals(file) || file.getParent() == null) {
            throw new IllegalArgumentException("junitEventPathInvalid");
        }
        try (var directory = Files.newDirectoryStream(file.getRoot())) {
            if (!(directory instanceof SecureDirectoryStream<Path> secure)) {
                throw new IOException("junitSecureDirectoryUnsupported");
            }
            write(secure, file.getRoot().relativize(file), ByteBuffer.wrap(bytes));
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
