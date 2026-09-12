package io.github.hwainhwang.sentinel.mutation;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Nearest owning Maven module and the target path relative to that module. */
record MavenModule(Path root, String relativeSource) {
    static MavenModule locate(Path projectRoot, String projectRelativeSource) {
        Path source = projectRoot.resolve(projectRelativeSource).normalize();
        Path candidate = source.getParent();
        while (candidate != null && candidate.startsWith(projectRoot)) {
            if (validPom(candidate.resolve("pom.xml"))) {
                String relative = candidate.relativize(source).toString().replace('\\', '/');
                return new MavenModule(candidate, relative);
            }
            if (candidate.equals(projectRoot)) {
                break;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalArgumentException("mavenModuleMissing");
    }

    private static boolean validPom(Path pom) {
        return !Files.isSymbolicLink(pom)
                && Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS);
    }
}
