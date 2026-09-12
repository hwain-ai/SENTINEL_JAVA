package io.github.hwainhwang.sentinel.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DoctorScriptTest {
    @Test
    void reportsVerifiedRuntimeCoverageAndMutationSourceProvenance() throws Exception {
        Process process = new ProcessBuilder("./scripts/doctor.sh")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.waitFor(), output);
        assertTrue(output.contains("\"schemaVersion\":\"sentinel-java-doctor-v1\""));
        assertTrue(output.contains("\"passed\":true"));
        assertTrue(output.contains("\"java\":\"17.0.20.1+1\""));
        assertTrue(output.contains("\"maven\":\"3.9.16\""));
        assertTrue(output.contains("\"jacoco\":\"0.8.12\""));
        assertTrue(output.contains(
                "\"mutate4javaCommit\":\"7b05fdd71e8fe36327aff837806dfbff86af0572\""));
        assertTrue(output.contains(
                "\"mutate4javaArchiveSha256\":"
                        + "\"762c4b91ef592fbd07dace1189626013b3935c0fa41407242c52ebb1ffdeb34f\""));
        assertTrue(output.contains(
                "\"mutate4javaJarSha256\":"
                        + "\"117ef17d0dfe32e50f0449a652b83c92a377858cdcdd9c1d191a8d4a69d70ad3\""));
        assertTrue(output.contains("\"mutationBackendStatus\":\"runtime-locked\""));
    }
}
