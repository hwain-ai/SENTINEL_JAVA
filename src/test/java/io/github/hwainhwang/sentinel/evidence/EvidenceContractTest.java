package io.github.hwainhwang.sentinel.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidenceContractTest {
    private static final ProjectState PROJECT = ProjectState.parse(
            HexFormat.of().parseHex(EvidenceFixtures.PROJECT_FILE_HEX));

    @Test
    void buildsAndAuthenticatesTheExactStrictCheckGolden() throws Exception {
        byte[] payload = EvidenceContract.build(EvidenceFixtures.strictCheckBody(), PROJECT);
        Map<String, Object> document = EvidenceContract.validate(payload, PROJECT);

        assertEquals(
                "40869448a1bc56bdc0a7c3cd528db367d838f71253733fab45d5e30a216d72ba",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)));
        assertEquals(
                "1aaaa38f58a49f6d64bcda9f05037e4432c88766f02a3000f8d9ef520fffc66e",
                document.get("hmacSha256"));
        assertEquals("1", document.get("commitSequence"));
        assertFalse(document.containsKey("run"));
        assertNull(document.get("sourceRunId"));
    }

    @Test
    void matchesTheMutationFailureAndLocalCacheSpecGoldens() throws Exception {
        assertGolden(
                EvidenceFixtures.strictMutationFailureBody(),
                "63556501d29403c3e7ee1d9b8a58720155265f5aae2b0fb45078c93e39fb8939",
                "0527699d583a32d24e28b30bf4dba11ab6d4ccdec5a33bf22759b528178c4c1e");
        assertGolden(
                EvidenceFixtures.localCacheCrapBody(),
                "8756ed2baf7716a6ffb14c769b3229c7266454fcf4124848fd45e2f9d90f0754",
                "99e00cb7efeaee7e2625a9a6ae93817bc1f4b39f917bd028a2aa6a0963e53163");
    }

    @Test
    void rejectsTamperedOrLegacyWireShapes() {
        byte[] payload = EvidenceContract.build(EvidenceFixtures.strictCheckBody(), PROJECT);
        @SuppressWarnings("unchecked")
        Map<String, Object> document = (Map<String, Object>) CanonicalJson.readFile(payload);
        String hmac = document.get("hmacSha256").toString();
        document.put("hmacSha256", (hmac.startsWith("0") ? "1" : "0") + hmac.substring(1));
        assertCode("evidenceHmacMismatch", () -> EvidenceContract.validate(
                CanonicalJson.file(document), PROJECT));

        Map<String, Object> legacy = EvidenceFixtures.copy(document);
        legacy.remove("commitSequence");
        legacy.put("run", Map.of("runId", legacy.get("runId"), "sequence", 1));
        assertCode("evidenceFieldsInvalid", () -> EvidenceContract.validate(
                CanonicalJson.file(legacy), PROJECT));
    }

    @Test
    void joinsFreshAndCacheObservationsToSourceRunIds() {
        Map<String, Object> fresh = EvidenceFixtures.strictCheckBody();
        fresh.put("sourceRunId", "22222222-2222-4222-8222-222222222222");
        assertCode("sourceRunIdInvalid", () -> EvidenceContract.build(fresh, PROJECT));

        Map<String, Object> cache = EvidenceFixtures.strictCheckBody();
        cache.put("mode", "local");
        cache.put("certification", false);
        cache.put("observationSource", "cache");
        cache.put("sourceRunId", null);
        cache.put("eventCount", 0);
        cache.put("events", List.of());
        assertCode("sourceRunIdInvalid", () -> EvidenceContract.build(cache, PROJECT));

        cache.put("sourceRunId", "22222222-2222-4222-8222-222222222222");
        EvidenceContract.build(cache, PROJECT);
    }

    @Test
    void enforcesOneBasedContiguousEventManifestOrdinals() {
        Map<String, Object> body = EvidenceFixtures.strictCheckBody();
        body.put("events", List.of(Map.of(
                "filename", "00000000000000000000000000000002.json",
                "sha256", "a".repeat(64))));
        assertCode("eventManifestOrdinalInvalid", () -> EvidenceContract.build(body, PROJECT));

        body.put("eventCount", 2);
        assertCode("eventManifestCountMismatch", () -> EvidenceContract.build(body, PROJECT));
    }

    @Test
    void assignsEventIdsAfterSortingPublicFingerprintsByUtf8Bytes() {
        List<Map<String, String>> events = EvidenceContract.assignEventOrdinals(List.of(
                "hmac-sha256:" + "f".repeat(64),
                "hmac-sha256:" + "0".repeat(64),
                "hmac-sha256:" + "a".repeat(64)));

        assertEquals("00000000000000000000000000000001", events.get(0).get("eventId"));
        assertEquals("hmac-sha256:" + "0".repeat(64), events.get(0).get("fingerprint"));
        assertEquals("00000000000000000000000000000002.json", events.get(1).get("filename"));
        assertEquals("hmac-sha256:" + "f".repeat(64), events.get(2).get("fingerprint"));

        assertCode("findingFingerprintInvalid", () -> EvidenceContract.assignEventOrdinals(
                List.of("a".repeat(64))));
        assertCode("findingFingerprintDuplicate", () -> EvidenceContract.assignEventOrdinals(
                List.of("hmac-sha256:" + "a".repeat(64),
                        "hmac-sha256:" + "a".repeat(64))));
    }

    @Test
    void validatesHistoricalEvidenceAfterFingerprintKeyRotation() {
        Map<String, Object> body = EvidenceFixtures.strictCheckBody();
        byte[] payload = EvidenceContract.build(body, PROJECT);
        ProjectState rotated = EvidenceFixtures.rotatedProject();

        assertEquals(1L, EvidenceContract.validate(payload, rotated).get("keyEpoch"));
        assertCode("keyEpochInvalid", () -> EvidenceContract.build(body, rotated));

        Map<String, Object> future = EvidenceFixtures.strictCheckBody();
        future.put("keyEpoch", 3);
        assertCode("keyEpochInvalid", () -> EvidenceContract.build(future, rotated));
    }

    @Test
    void validatesIdentityTimeTerminalAndProjectBinding() {
        assertFieldCode("schemaVersion", "evidence-v1", "evidenceSchemaVersionInvalid");
        assertFieldCode("runId", "11111111111111111111111111111111", "runIdInvalid");
        assertFieldCode("commitSequence", "0", "commitSequenceInvalid");
        assertFieldCode("completedAtUtc", "2026-09-03T11:59:59Z", "evidenceTimeOrderInvalid");
        assertFieldCode("completedAtUtc", "2026-09-03T12:00:01.100Z", "utcTimestampInvalid");
        assertFieldCode("exitCode", 2, "terminalStatusExitCodeMismatch");
        assertFieldCode("certification", false, "certificationInvalid");
        assertFieldCode("keyEpoch", 2, "keyEpochInvalid");
        assertFieldCode("projectStateHmac", "0".repeat(64), "projectStateBindingInvalid");
    }

    @Test
    void validatesComponentSemanticsAndSortedDiagnostics() {
        Map<String, Object> wrongCommand = EvidenceFixtures.strictCheckBody();
        wrongCommand.put("command", "mutation");
        assertCode(
                "evidenceComponentsInvalid",
                () -> EvidenceContract.build(wrongCommand, PROJECT));

        Map<String, Object> diagnostics = EvidenceFixtures.strictCheckBody();
        diagnostics.put("diagnosticCodes", List.of("zCode", "aCode"));
        assertCode(
                "diagnosticCodesInvalid",
                () -> EvidenceContract.build(diagnostics, PROJECT));

        Map<String, Object> body = EvidenceFixtures.strictCheckBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> mutation = (Map<String, Object>) ((Map<?, ?>) body.get("components"))
                .get("mutation");
        mutation.put("inScope", 2);
        Map<String, Object> invalidMutation = body;
        assertCode("mutationComponentInvalid", () -> EvidenceContract.build(
                invalidMutation, PROJECT));

        Map<String, Object> emptyInventory = EvidenceFixtures.strictCheckBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> crap = (Map<String, Object>) ((Map<?, ?>) emptyInventory
                .get("components")).get("crap");
        crap.put("callableCount", 0);
        assertCode("crapComponentSemanticsInvalid", () -> EvidenceContract.build(
                emptyInventory, PROJECT));
    }

    @Test
    void rejectsStrictCacheAndRequiresBackendErrorForToolErrorMutants() {
        Map<String, Object> cache = EvidenceFixtures.strictCheckBody();
        cache.put("observationSource", "cache");
        cache.put("sourceRunId", "22222222-2222-4222-8222-222222222222");
        cache.put("eventCount", 0);
        cache.put("events", List.of());
        assertCode("strictCacheInvalid", () -> EvidenceContract.build(cache, PROJECT));

        Map<String, Object> body = EvidenceFixtures.strictCheckBody();
        body.put("command", "mutation");
        body.put("certification", false);
        body.put("terminalStatus", "qualityFailed");
        body.put("exitCode", 2);
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) body.get("components");
        @SuppressWarnings("unchecked")
        Map<String, Object> mutation = (Map<String, Object>) components.get("mutation");
        components.remove("crap");
        mutation.put("killed", 0);
        mutation.put("toolError", 1);
        mutation.put("pass", false);
        assertCode("terminalStatusPrecedenceInvalid", () -> EvidenceContract.build(body, PROJECT));
    }

    private static void assertFieldCode(String field, Object value, String code) {
        Map<String, Object> body = EvidenceFixtures.strictCheckBody();
        body.put(field, value);
        assertCode(code, () -> EvidenceContract.build(body, PROJECT));
    }

    private static void assertGolden(
            Map<String, Object> body, String expectedHmac, String expectedFileSha) throws Exception {
        byte[] payload = EvidenceContract.build(body, PROJECT);
        Map<String, Object> document = EvidenceContract.validate(payload, PROJECT);
        assertEquals(expectedHmac, document.get("hmacSha256"));
        assertEquals(
                expectedFileSha,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)));
    }

    private static void assertCode(String expected, Runnable operation) {
        EvidenceContractException error = assertThrows(
                EvidenceContractException.class, operation::run);
        assertEquals(expected, error.code());
    }
}
