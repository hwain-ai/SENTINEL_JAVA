package io.github.hwainhwang.sentinel.evidence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectStateTest {
    @Test
    void parsesTheExactSpecGoldenAndDerivesTheProjectBinding() {
        ProjectState state = ProjectState.parse(HexFormat.of().parseHex(
                EvidenceFixtures.PROJECT_FILE_HEX));

        assertEquals(1, state.keyEpoch());
        assertEquals(
                "441395de4352207dc696516a31efa8fb34fc5d4df9a9005537342cda21e84354",
                state.bindingHmac());
        assertNotEquals(
                HexFormat.of().formatHex(state.fingerprintHmacKey()),
                HexFormat.of().formatHex(state.cleanupLeaseKey()));
        assertArrayEquals(HexFormat.of().parseHex(EvidenceFixtures.CLEANUP_KEY_HEX),
                state.cleanupLeaseKey());
    }

    @Test
    void acceptsTheMaximumSafeKeyEpoch() {
        Map<String, Object> document = ProjectState.parse(HexFormat.of().parseHex(
                EvidenceFixtures.PROJECT_FILE_HEX)).document();
        document.put("keyEpoch", 9_007_199_254_740_991L);

        assertEquals(
                9_007_199_254_740_991L,
                ProjectState.parse(CanonicalJson.file(document)).keyEpoch());
    }

    @Test
    void projectBindingSurvivesFingerprintKeyRotation() {
        ProjectState original = ProjectState.parse(HexFormat.of().parseHex(
                EvidenceFixtures.PROJECT_FILE_HEX));
        ProjectState rotated = EvidenceFixtures.rotatedProject();

        assertEquals(2, rotated.keyEpoch());
        assertEquals(original.bindingHmac(), rotated.bindingHmac());
    }

    @Test
    void rejectsLegacyPaddedOrUnseparatedState() {
        Map<String, Object> base = ProjectState.parse(HexFormat.of().parseHex(
                EvidenceFixtures.PROJECT_FILE_HEX)).document();

        Map<String, Object> legacy = new java.util.LinkedHashMap<>(base);
        legacy.put("schemaVersion", "project-state-v1");
        assertCode("projectStateSchemaVersionInvalid", () -> ProjectState.from(legacy));

        Map<String, Object> padded = new java.util.LinkedHashMap<>(base);
        padded.put("fingerprintHmacKey", padded.get("fingerprintHmacKey") + "=");
        assertCode("projectStateEncodingInvalid", () -> ProjectState.from(padded));

        Map<String, Object> same = new java.util.LinkedHashMap<>(base);
        same.put("cleanupLeaseKey", same.get("fingerprintHmacKey"));
        assertCode("projectStateKeysNotSeparated", () -> ProjectState.from(same));
    }

    @Test
    void rejectsWrongFieldsVersionAndEpoch() {
        Map<String, Object> base = ProjectState.parse(HexFormat.of().parseHex(
                EvidenceFixtures.PROJECT_FILE_HEX)).document();
        Map<String, Object> missing = new java.util.LinkedHashMap<>(base);
        missing.remove("stateVersion");
        assertCode("projectStateFieldsInvalid", () -> ProjectState.from(missing));

        Map<String, Object> version = new java.util.LinkedHashMap<>(base);
        version.put("stateVersion", "v1");
        assertCode("projectStateVersionInvalid", () -> ProjectState.from(version));

        Map<String, Object> epoch = new java.util.LinkedHashMap<>(base);
        epoch.put("keyEpoch", 0);
        assertCode("keyEpochInvalid", () -> ProjectState.from(epoch));

        Map<String, Object> booleanEpoch = new java.util.LinkedHashMap<>(base);
        booleanEpoch.put("keyEpoch", true);
        assertCode("keyEpochInvalid", () -> ProjectState.from(booleanEpoch));

        Map<String, Object> unsafeEpoch = new java.util.LinkedHashMap<>(base);
        unsafeEpoch.put("keyEpoch", 9_007_199_254_740_992L);
        assertCode("keyEpochInvalid", () -> ProjectState.from(unsafeEpoch));
    }

    private static void assertCode(String expected, Runnable operation) {
        EvidenceContractException error = assertThrows(
                EvidenceContractException.class, operation::run);
        assertEquals(expected, error.code());
    }
}
