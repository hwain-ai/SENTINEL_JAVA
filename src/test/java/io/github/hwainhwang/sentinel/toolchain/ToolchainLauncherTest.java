package io.github.hwainhwang.sentinel.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolchainLauncherTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void pendingLockStopsBootstrapBeforeCreatingAPartialTree() throws Exception {
        Path repository = copyLauncherRepository();
        demoteRepositoryLock(repository);

        Result result = run(repository, "scripts/bootstrap-toolchain.sh");

        assertNotEquals(0, result.exitCode());
        assertTrue(result.output().contains("pending"));
        assertFalse(Files.exists(repository.resolve(".toolchain")));
    }

    @Test
    void hostileEnvironmentAndPartialJavaTreeCannotSelectAmbientRuntime() throws Exception {
        Path repository = copyLauncherRepository();
        demoteRepositoryLock(repository);
        Path fakeJava = repository.resolve(".toolchain/jdk-17.0.20.1+1/bin/java");
        Files.createDirectories(fakeJava.getParent());
        Files.writeString(fakeJava, "#!/usr/bin/bash\necho AMBIENT_EXECUTED\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(
                fakeJava,
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));

        Result result = run(
                repository,
                "scripts/java.sh",
                Map.of(
                        "JAVA_HOME", "/hostile/java",
                        "JAVA_TOOL_OPTIONS", "-javaagent:/hostile/agent.jar",
                        "_JAVA_OPTIONS", "-Dhostile=true",
                        "JDK_JAVA_OPTIONS", "--show-version",
                        "BASH_ENV", bashEnvironmentCanary(repository).toString()),
                "--version");

        assertNotEquals(0, result.exitCode());
        assertTrue(result.output().contains("pending"));
        assertFalse(result.output().contains("AMBIENT_EXECUTED"));
        assertFalse(Files.exists(repository.resolve("bash-env-executed")));
    }

    @Test
    void hostileMavenEnvironmentAndPartialTreeFailBeforeExecution() throws Exception {
        Path repository = copyLauncherRepository();
        demoteRepositoryLock(repository);
        Path fakeMaven = repository.resolve(".toolchain/apache-maven-3.9.16/bin/mvn");
        Files.createDirectories(fakeMaven.getParent());
        Files.writeString(fakeMaven, "#!/usr/bin/bash\necho MAVEN_EXECUTED\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(
                fakeMaven,
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));

        Result result = run(
                repository,
                "scripts/mvn.sh",
                Map.of(
                        "MAVEN_ARGS", "--settings /hostile/settings.xml",
                        "MAVEN_OPTS", "-Dhostile=true",
                        "M2_HOME", "/hostile/maven",
                        "MAVEN_HOME", "/hostile/maven",
                        "BASH_ENV", bashEnvironmentCanary(repository).toString()),
                "--version");

        assertNotEquals(0, result.exitCode());
        assertTrue(result.output().contains("pending"));
        assertFalse(result.output().contains("MAVEN_EXECUTED"));
        assertFalse(Files.exists(repository.resolve("bash-env-executed")));
    }

    @Test
    void lockedBinaryAloneCannotMasqueradeAsACompleteInstalledTree() throws Exception {
        Path repository = copyLauncherRepository();
        Path fakeJava = repository.resolve(".toolchain/jdk-17.0.20.1+1/bin/java");
        Files.createDirectories(fakeJava.getParent());
        Files.writeString(fakeJava, "#!/usr/bin/bash\necho PARTIAL_EXECUTED\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(
                fakeJava,
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        ownerOnly(repository.resolve(".toolchain"));
        Result result = run(repository, "scripts/java.sh", "--version");

        assertNotEquals(0, result.exitCode());
        assertTrue(result.output().contains("installed tree manifest mismatch"));
        assertFalse(result.output().contains("PARTIAL_EXECUTED"));
    }

    @Test
    void writableToolchainContainerIsRejectedBeforeTreeSelection() throws Exception {
        Path repository = copyLauncherRepository();
        Path toolchain = repository.resolve(".toolchain");
        Files.createDirectory(toolchain);
        Files.setPosixFilePermissions(toolchain, EnumSet.allOf(PosixFilePermission.class));

        Result result = run(repository, "scripts/mvn.sh", "--version");

        assertNotEquals(0, result.exitCode());
        assertTrue(result.output().contains("private directory"));
    }

    @Test
    void mavenUserSettingsAreRejectedBeforeAnyRuntimeCanExecute() throws Exception {
        Path repository = copyLauncherRepository();
        Path settings = repository.resolve(".toolchain/home/.m2/settings.xml");
        Files.createDirectories(settings.getParent());
        ownerOnly(repository.resolve(".toolchain"));
        ownerOnly(repository.resolve(".toolchain/home"));
        Files.writeString(settings, "<settings/>", StandardCharsets.UTF_8);
        Result result = run(repository, "scripts/mvn.sh", "--version");

        assertNotEquals(0, result.exitCode());
        assertTrue(result.output().contains("Maven user configuration is not allowed"));

        Files.delete(settings);
        Files.writeString(
                settings.resolveSibling("toolchains.xml"),
                "<toolchains/>",
                StandardCharsets.UTF_8);
        Result toolchains = run(repository, "scripts/mvn.sh", "--version");

        assertNotEquals(0, toolchains.exitCode());
        assertTrue(toolchains.output().contains("Maven user configuration is not allowed"));
    }

    @Test
    void repositoryLockAndLaunchersSelectTheVerifiedToolchains() throws Exception {
        Path repository = Path.of("").toAbsolutePath().normalize();
        String lock = Files.readString(repository.resolve("toolchain.lock.json"), StandardCharsets.UTF_8);

        assertFalse(lock.contains("pending"));
        assertTrue(lock.contains("3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e"));
        assertTrue(lock.contains("80ffca22aed9e8b9713a232f3394fd81d7f20322df75efdb2b047dbd3e3a23bb"));

        Result java = run(repository, "scripts/java.sh", "--version");
        Result maven = run(repository, "scripts/mvn.sh", "--version");

        assertEquals(0, java.exitCode(), java.output());
        assertTrue(java.output().contains("17.0.20.1"));
        assertEquals(0, maven.exitCode(), maven.output());
        assertTrue(maven.output().contains("Apache Maven 3.9.16"));
    }

    @Test
    void duplicateAndPlaceholderLockValuesFailClosed() throws Exception {
        Path repository = copyLauncherRepository();
        Path lock = repository.resolve("toolchain.lock.json");
        String original = Files.readString(lock, StandardCharsets.UTF_8);
        Files.writeString(
                lock,
                original.replaceFirst(
                        "\\\"status\\\": \\\"locked\\\"",
                        "\\\"status\\\": \\\"bootstrap-pending\\\", \\\"status\\\": \\\"locked\\\""),
                StandardCharsets.UTF_8);

        Result duplicate = runLockTool(repository, "java", "--require-locked");

        assertNotEquals(0, duplicate.exitCode());
        assertTrue(duplicate.output().contains("duplicate"));

        Files.writeString(
                lock,
                original.replace(
                        "3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e",
                        "0".repeat(64)),
                StandardCharsets.UTF_8);
        Result placeholder = runLockTool(repository, "java", "--require-locked");

        assertNotEquals(0, placeholder.exitCode());
        assertTrue(placeholder.output().contains("placeholder"));
    }

    @Test
    void lockedPlatformAndJavaVendorMustRemainTypedText() throws Exception {
        Path repository = copyLauncherRepository();
        Path lock = repository.resolve("toolchain.lock.json");
        String original = Files.readString(lock, StandardCharsets.UTF_8);
        Files.writeString(
                lock,
                original.replace("\"platform\": \"linux-x64\"", "\"platform\": 17"),
                StandardCharsets.UTF_8);

        Result platform = runLockTool(repository, "java", "--require-locked");

        assertNotEquals(0, platform.exitCode());
        assertTrue(platform.output().contains("platform"));

        Files.writeString(
                lock,
                original.replace("\"vendor\": \"Eclipse Temurin\"", "\"vendor\": null"),
                StandardCharsets.UTF_8);

        Result vendor = runLockTool(repository, "java", "--require-locked");

        assertNotEquals(0, vendor.exitCode());
        assertTrue(vendor.output().contains("vendor"));
    }

    @Test
    void lockVersionOutputMustMatchTheVerifiedBinaryOutput() throws Exception {
        Path repository = copyLauncherRepository();

        Result exact = runLockTool(
                repository,
                "java",
                "--require-locked",
                "--verify-version-output",
                "openjdk version \"17.0.20.1\" 2026-08-18");
        Result wrong = runLockTool(
                repository,
                "java",
                "--require-locked",
                "--verify-version-output",
                "openjdk version \"17.0.19\"");

        assertEquals(0, exact.exitCode(), exact.output());
        assertNotEquals(0, wrong.exitCode());
        assertTrue(wrong.output().contains("version output mismatch"));
    }

    @Test
    void explicitBashAndMavenConfigurationOverridesAreRejected() throws Exception {
        Path repository = Path.of("").toAbsolutePath().normalize();

        Result explicit = runCommand(
                repository,
                Map.of(),
                "/usr/bin/bash",
                repository.resolve("scripts/mvn.sh").toString(),
                "--version");
        Result repositoryOverride = run(
                repository,
                "scripts/mvn.sh",
                "-Dmaven.repo.local=/tmp/untrusted-sentinel-m2",
                "--version");
        Result settingsOverride = run(
                repository,
                "scripts/mvn.sh",
                "--settings=/tmp/untrusted-settings.xml",
                "--version");
        Result javaExplicit = runCommand(
                repository,
                Map.of(),
                "/usr/bin/bash",
                repository.resolve("scripts/java.sh").toString(),
                "--version");
        Result bootstrapExplicit = runCommand(
                repository,
                Map.of(),
                "/usr/bin/bash",
                repository.resolve("scripts/bootstrap-toolchain.sh").toString());

        assertNotEquals(0, explicit.exitCode());
        assertTrue(explicit.output().contains("must be executed directly"));
        assertNotEquals(0, repositoryOverride.exitCode());
        assertTrue(repositoryOverride.output().contains("Maven argument is not allowed"));
        assertNotEquals(0, settingsOverride.exitCode());
        assertTrue(settingsOverride.output().contains("Maven argument is not allowed"));
        assertNotEquals(0, javaExplicit.exitCode());
        assertTrue(javaExplicit.output().contains("must be executed directly"));
        assertNotEquals(0, bootstrapExplicit.exitCode());
        assertTrue(bootstrapExplicit.output().contains("must be executed directly"));
    }

    @Test
    void spoofedMarkerWithHostileBashEnvironmentCannotReachMaven() throws Exception {
        Path repository = Path.of("").toAbsolutePath().normalize();
        Path hostile = temporaryDirectory.resolve("hostile-bash-env.sh");
        Path marker = temporaryDirectory.resolve("hostile-executed");
        Files.writeString(
                hostile,
                "/usr/bin/touch '" + marker + "'\n",
                StandardCharsets.UTF_8);

        Result result = runCommandClean(
                repository,
                Map.of(
                        "BASH_ENV", hostile.toString(),
                        "SENTINEL_JAVA_SEALED_ENTRY", "direct-v1"),
                "/usr/bin/bash",
                "--noprofile",
                "--norc",
                repository.resolve("scripts/mvn.sh").toString(),
                "--version");

        assertNotEquals(0, result.exitCode());
        assertTrue(Files.exists(marker));
        assertTrue(result.output().contains("must be executed directly"));
        assertFalse(result.output().contains("Apache Maven 3.9.16"));
    }

    @Test
    void exactShebangReplayDocumentsTheShellOnlyBoundary() throws Exception {
        Path repository = Path.of("").toAbsolutePath().normalize();

        Result result = runCommandClean(
                repository,
                Map.of("SENTINEL_JAVA_SEALED_ENTRY", "direct-v1"),
                "/usr/bin/bash",
                "--noprofile",
                "--norc",
                repository.resolve("scripts/mvn.sh").toString(),
                "--version");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Apache Maven 3.9.16"));
    }

    @Test
    void pomPinsTheReleaseIdentityAndDefaultLifecyclePlugins() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);

        assertTrue(pom.contains("<version>0.1.0-rc.1</version>"));
        assertTrue(pom.contains(
                "<project.build.outputTimestamp>2000-01-01T00:00:00Z</project.build.outputTimestamp>"));
        assertTrue(pom.contains("<artifactId>maven-resources-plugin</artifactId>"));
        assertTrue(pom.contains("<artifactId>maven-jar-plugin</artifactId>"));
        assertTrue(pom.contains("<addDefaultImplementationEntries>true</addDefaultImplementationEntries>"));
    }

    @Test
    void treeDigestRejectsWritableRootsHardLinksAndBrokenLinks() throws Exception {
        Path repository = copyLauncherRepository();
        Path writable = temporaryDirectory.resolve("writable-tree");
        Files.createDirectory(writable);
        Files.setPosixFilePermissions(writable, EnumSet.allOf(PosixFilePermission.class));

        Result writableResult = runLockTool(
                repository,
                "java",
                "--print-tree-digest",
                writable.toString());

        assertNotEquals(0, writableResult.exitCode());
        assertTrue(writableResult.output().contains("permissions"));

        Path external = temporaryDirectory.resolve("external-file");
        Files.writeString(external, "external", StandardCharsets.UTF_8);
        Path hardLinkTree = temporaryDirectory.resolve("hard-link-tree");
        Files.createDirectory(hardLinkTree);
        Files.createLink(hardLinkTree.resolve("alias"), external);
        Result hardLink = runLockTool(
                repository,
                "java",
                "--print-tree-digest",
                hardLinkTree.toString());

        assertNotEquals(0, hardLink.exitCode());
        assertTrue(hardLink.output().contains("hard link"));

        Path brokenLinkTree = temporaryDirectory.resolve("broken-link-tree");
        Files.createDirectory(brokenLinkTree);
        Files.createSymbolicLink(brokenLinkTree.resolve("missing"), Path.of("absent"));
        Result brokenLink = runLockTool(
                repository,
                "java",
                "--print-tree-digest",
                brokenLinkTree.toString());

        assertNotEquals(0, brokenLink.exitCode());
        assertTrue(brokenLink.output().contains("symlink target"));

        Path cyclicLinkTree = temporaryDirectory.resolve("cyclic-link-tree");
        Files.createDirectory(cyclicLinkTree);
        Files.createSymbolicLink(cyclicLinkTree.resolve("loop"), Path.of("loop"));
        Result cyclicLink = runLockTool(
                repository,
                "java",
                "--print-tree-digest",
                cyclicLinkTree.toString());

        assertNotEquals(0, cyclicLink.exitCode());
        assertTrue(cyclicLink.output().contains("toolchain error"));
        assertFalse(cyclicLink.output().contains("Traceback"));
    }

    @Test
    void privateRuntimeDirectoriesRequireOwnerOnlyPermissions() throws Exception {
        Path repository = copyLauncherRepository();
        Path directory = temporaryDirectory.resolve("runtime-directory");
        Files.createDirectory(directory);

        Result unsafe = runLockTool(
                repository,
                "java",
                "--verify-private-directory",
                directory.toString());

        assertNotEquals(0, unsafe.exitCode());
        assertTrue(unsafe.output().contains("private directory"));

        Files.setPosixFilePermissions(
                directory,
                EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
        Result safe = runLockTool(
                repository,
                "java",
                "--verify-private-directory",
                directory.toString());

        assertEquals(0, safe.exitCode(), safe.output());
    }

    private static void demoteRepositoryLock(Path repository) throws IOException {
        Path lock = repository.resolve("toolchain.lock.json");
        String text = Files.readString(lock, StandardCharsets.UTF_8)
                .replaceFirst("\"status\": \"locked\"", "\"status\": \"bootstrap-pending\"");
        Files.writeString(lock, text, StandardCharsets.UTF_8);
    }

    private static void ownerOnly(Path directory) throws IOException {
        Files.setPosixFilePermissions(
                directory,
                EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
    }

    private static Path bashEnvironmentCanary(Path repository) throws IOException {
        Path canary = repository.resolve("hostile-bash-env.sh");
        Files.writeString(
                canary,
                "touch '" + repository.resolve("bash-env-executed") + "'\n",
                StandardCharsets.UTF_8);
        return canary;
    }

    private Path copyLauncherRepository() throws IOException {
        Path source = Path.of("").toAbsolutePath().normalize();
        Path destination = temporaryDirectory.resolve("repository");
        Files.createDirectories(destination.resolve("scripts"));
        Files.copy(source.resolve("toolchain.lock.json"), destination.resolve("toolchain.lock.json"));
        for (String script : new String[] {
            "bootstrap-toolchain.sh", "java.sh", "mvn.sh", "toolchain_lock.py"
        }) {
            Files.copy(
                    source.resolve("scripts").resolve(script),
                    destination.resolve("scripts").resolve(script),
                    StandardCopyOption.COPY_ATTRIBUTES);
        }
        return destination;
    }

    private static Result run(Path repository, String script, String... arguments)
            throws IOException, InterruptedException {
        return run(repository, script, Map.of(), arguments);
    }

    private static Result run(
            Path repository, String script, Map<String, String> hostile, String... arguments)
            throws IOException, InterruptedException {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add(repository.resolve(script).toString());
        command.addAll(java.util.List.of(arguments));
        return runCommand(repository, hostile, command.toArray(String[]::new));
    }

    private static Result runLockTool(Path repository, String... arguments)
            throws IOException, InterruptedException {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("/usr/bin/python3");
        command.add("-I");
        command.add(repository.resolve("scripts/toolchain_lock.py").toString());
        command.add(repository.resolve("toolchain.lock.json").toString());
        command.addAll(java.util.List.of(arguments));
        return runCommand(repository, Map.of(), command.toArray(String[]::new));
    }

    private static Result runCommand(
            Path repository, Map<String, String> hostile, String... command)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.directory(repository.toFile());
        builder.environment().putAll(hostile);
        return finish(builder);
    }

    private static Result runCommandClean(
            Path repository, Map<String, String> environment, String... command)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.directory(repository.toFile());
        builder.environment().clear();
        builder.environment().putAll(environment);
        return finish(builder);
    }

    private static Result finish(ProcessBuilder builder)
            throws IOException, InterruptedException {
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.waitFor(), output);
    }

    private record Result(int exitCode, String output) {}
}
