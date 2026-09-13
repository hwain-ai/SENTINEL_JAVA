package io.github.hwainhwang.sentinel.mutation;

import java.nio.file.Files;
import java.nio.file.Path;

/** Where the locked JDK and Maven live under the checker's own .toolchain on this platform. */
final class LockedToolchain {
    private static final String JDK = ".toolchain/jdk-17.0.20.1+1";
    private static final String MAVEN = ".toolchain/apache-maven-3.9.16";

    private LockedToolchain() {
        throw new AssertionError("no instances");
    }

    /** macOS JDK archives are application bundles; JAVA_HOME sits in Contents/Home. */
    static Path javaHome(Path toolRoot) {
        Path bundle = toolRoot.resolve(JDK).resolve("Contents/Home");
        return Files.isDirectory(bundle) ? bundle : toolRoot.resolve(JDK);
    }

    static Path mavenHome(Path toolRoot) {
        return toolRoot.resolve(MAVEN);
    }
}
