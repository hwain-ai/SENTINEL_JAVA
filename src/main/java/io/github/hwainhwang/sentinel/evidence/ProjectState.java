package io.github.hwainhwang.sentinel.evidence;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validated private project identity and separated keys for evidence authentication. */
public final class ProjectState {
    private static final Set<String> FIELDS = Set.of(
            "cleanupLeaseKey",
            "fingerprintHmacKey",
            "keyEpoch",
            "projectIdentifier",
            "schemaVersion",
            "stateVersion");
    private static final Pattern BASE64URL = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final String BINDING_DOMAIN = "SENTINEL\0project-state-binding\0v1\0";

    private final Map<String, Object> document;
    private final byte[] projectIdentifier;
    private final byte[] fingerprintHmacKey;
    private final byte[] cleanupLeaseKey;
    private final long keyEpoch;

    private ProjectState(
            Map<String, Object> document,
            byte[] projectIdentifier,
            byte[] fingerprintHmacKey,
            byte[] cleanupLeaseKey,
            long keyEpoch) {
        this.document = Map.copyOf(document);
        this.projectIdentifier = projectIdentifier.clone();
        this.fingerprintHmacKey = fingerprintHmacKey.clone();
        this.cleanupLeaseKey = cleanupLeaseKey.clone();
        this.keyEpoch = keyEpoch;
    }

    public static ProjectState parse(byte[] payload) {
        return from(CanonicalJson.readFile(payload));
    }

    public static ProjectState from(Object value) {
        Map<String, Object> document = ContractValues.map(value, "projectStateFieldsInvalid");
        ContractValues.exactFields(document, FIELDS, "projectStateFieldsInvalid");
        requireVersion(document);
        long epoch = ContractValues.safeInteger(document.get("keyEpoch"), true, "keyEpochInvalid");
        byte[] identifier = decode(document.get("projectIdentifier"), 16);
        byte[] fingerprint = decode(document.get("fingerprintHmacKey"), 32);
        byte[] cleanup = decode(document.get("cleanupLeaseKey"), 32);
        if (MessageDigest.isEqual(fingerprint, cleanup)) {
            throw new EvidenceContractException("projectStateKeysNotSeparated");
        }
        return new ProjectState(document, identifier, fingerprint, cleanup, epoch);
    }

    private static void requireVersion(Map<String, Object> document) {
        if (!"sentinel-project-state-v1".equals(document.get("schemaVersion"))) {
            throw new EvidenceContractException("projectStateSchemaVersionInvalid");
        }
        if (!"state-v1".equals(document.get("stateVersion"))) {
            throw new EvidenceContractException("projectStateVersionInvalid");
        }
    }

    private static byte[] decode(Object value, int size) {
        String encoded = ContractValues.string(value, "projectStateEncodingInvalid");
        if (!BASE64URL.matcher(encoded).matches()) {
            throw new EvidenceContractException("projectStateEncodingInvalid");
        }
        byte[] decoded = decodeBase64(encoded);
        String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);
        if (decoded.length != size || !canonical.equals(encoded)) {
            throw new EvidenceContractException("projectStateEncodingInvalid");
        }
        return decoded;
    }

    private static byte[] decodeBase64(String value) {
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException error) {
            throw new EvidenceContractException("projectStateEncodingInvalid", error);
        }
    }

    public long keyEpoch() {
        return keyEpoch;
    }

    public byte[] projectIdentifier() {
        return projectIdentifier.clone();
    }

    public byte[] fingerprintHmacKey() {
        return fingerprintHmacKey.clone();
    }

    public byte[] cleanupLeaseKey() {
        return cleanupLeaseKey.clone();
    }

    public Map<String, Object> document() {
        return new LinkedHashMap<>(document);
    }

    public String bindingHmac() {
        return EvidenceMac.direct(cleanupLeaseKey, BINDING_DOMAIN, projectIdentifier);
    }
}
