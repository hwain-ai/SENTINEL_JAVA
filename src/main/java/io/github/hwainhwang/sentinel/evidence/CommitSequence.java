package io.github.hwainhwang.sentinel.evidence;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact authenticated commit-sequence.json record and rollback checks. */
public final class CommitSequence {
    private static final String KEY_DOMAIN = "SENTINEL\0commit-sequence-key\0v1\0";
    private static final String MAC_DOMAIN = "SENTINEL\0commit-sequence\0v1\0";
    private static final Set<String> FIELDS = Set.of("hmacSha256", "lastAllocated", "version");

    private CommitSequence() {
        throw new AssertionError("no instances");
    }

    public static byte[] build(String lastAllocated, byte[] cleanupLeaseKey) {
        ContractValues.uint64(lastAllocated, false, "commitSequenceInvalid");
        Map<String, Object> body = body(lastAllocated);
        Map<String, Object> record = new LinkedHashMap<>(body);
        record.put("hmacSha256", EvidenceMac.record(
                cleanupLeaseKey, KEY_DOMAIN, MAC_DOMAIN, body));
        return CanonicalJson.file(record);
    }

    public static BigInteger validate(byte[] payload, byte[] cleanupLeaseKey) {
        Map<String, Object> document = ContractValues.map(
                CanonicalJson.readFile(payload), "commitSequenceFieldsInvalid");
        ContractValues.exactFields(document, FIELDS, "commitSequenceFieldsInvalid");
        requireVersion(document);
        BigInteger result = ContractValues.uint64(
                document.get("lastAllocated"), false, "commitSequenceInvalid");
        String actual = ContractValues.hex256(
                document.get("hmacSha256"), "commitSequenceHmacInvalid");
        String expected = EvidenceMac.record(
                cleanupLeaseKey, KEY_DOMAIN, MAC_DOMAIN, body(document.get("lastAllocated")));
        if (!EvidenceMac.matches(actual, expected)) {
            throw new EvidenceContractException("commitSequenceHmacMismatch");
        }
        return result;
    }

    public static BigInteger validateState(
            byte[] sequencePayload,
            byte[] cleanupLeaseKey,
            List<String> completedSequences,
            List<String> retentionHighWaters) {
        List<BigInteger> completed = values(
                completedSequences, false, "evidenceCommitSequenceInvalid");
        requireUnique(completed);
        List<BigInteger> retention = values(
                retentionHighWaters, true, "retentionHighWaterInvalid");
        BigInteger floor = maximum(completed, retention);
        if (sequencePayload == null) {
            return missingState(floor);
        }
        BigInteger current = validate(sequencePayload, cleanupLeaseKey);
        if (current.compareTo(floor) < 0) {
            throw new EvidenceContractException("commitSequenceRollback");
        }
        return current;
    }

    private static Map<String, Object> body(Object lastAllocated) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lastAllocated", lastAllocated);
        body.put("version", "commit-sequence-v1");
        return body;
    }

    private static void requireVersion(Map<String, Object> document) {
        if (!"commit-sequence-v1".equals(document.get("version"))) {
            throw new EvidenceContractException("commitSequenceVersionInvalid");
        }
    }

    private static List<BigInteger> values(List<String> values, boolean allowZero, String code) {
        if (values == null) {
            throw new EvidenceContractException(code);
        }
        java.util.ArrayList<BigInteger> result = new java.util.ArrayList<>();
        for (String value : values) {
            result.add(ContractValues.uint64(value, allowZero, code));
        }
        return List.copyOf(result);
    }

    private static void requireUnique(List<BigInteger> values) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new EvidenceContractException("commitSequenceDuplicate");
        }
    }

    private static BigInteger maximum(List<BigInteger> left, List<BigInteger> right) {
        return java.util.stream.Stream.concat(left.stream(), right.stream())
                .max(BigInteger::compareTo)
                .orElse(BigInteger.ZERO);
    }

    private static BigInteger missingState(BigInteger floor) {
        if (floor.signum() != 0) {
            throw new EvidenceContractException("commitSequenceMissing");
        }
        return BigInteger.ZERO;
    }
}
