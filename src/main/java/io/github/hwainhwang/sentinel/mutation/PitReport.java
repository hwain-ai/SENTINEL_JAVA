package io.github.hwainhwang.sentinel.mutation;

import java.util.Collection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.lang.constant.MethodTypeDesc;
import javax.lang.model.SourceVersion;

/** PIT observations, not certified SENTINEL evidence. */
public record PitReport(boolean partialCoverage, List<Result> results) {
    public PitReport {
        results = List.copyOf(results);
        Set<Identity> seen = new HashSet<>();
        for (Result result : results) {
            if (!seen.add(result.identity())) {
                throw new IllegalArgumentException("pitIdentityDuplicate");
            }
        }
    }

    /** PIT's location, mutator and ordered bytecode indexes, not line-based display text. */
    public record Identity(String mutatedClass, String mutatedMethod, String methodDescription,
            String mutator, List<Integer> indexes) {
        public Identity {
            requireClass(mutatedClass);
            requireMethod(mutatedMethod, methodDescription);
            requireText(mutator);
            indexes = numbers(indexes);
            if (indexes.isEmpty()) {
                throw new IllegalArgumentException("pitIndexesMissing");
            }
        }
    }

    public record Result(Identity identity, String sourceFile, int lineNumber,
            List<Integer> blocks, String description, String killingTest,
            int numberOfTestsRun, Status status) {
        public Result {
            requireSourceFile(sourceFile);
            requireText(description);
            blocks = numbers(blocks);
            if (identity == null || status == null || killingTest == null
                    || lineNumber < 1 || numberOfTestsRun < 0) {
                throw new IllegalArgumentException("pitFieldInvalid");
            }
        }
    }

    /** DetectionStatus from PIT 1.30.0: detected is not equivalent to an assertion kill. */
    public enum Status {
        KILLED(true, RawMutationStatus.KILLED),
        SURVIVED(false, RawMutationStatus.SURVIVED),
        TIMED_OUT(true, RawMutationStatus.TIMED_OUT),
        NON_VIABLE(true, RawMutationStatus.TOOL_ERROR),
        MEMORY_ERROR(true, RawMutationStatus.RUNTIME_ERROR),
        NOT_STARTED(false, RawMutationStatus.PENDING),
        STARTED(false, RawMutationStatus.PENDING),
        RUN_ERROR(true, RawMutationStatus.RUNTIME_ERROR),
        NO_COVERAGE(false, RawMutationStatus.UNCOVERED),
        EQUIVALENT(true, RawMutationStatus.IGNORED);

        private final boolean detected;
        private final RawMutationStatus raw;

        Status(boolean detected, RawMutationStatus raw) {
            this.detected = detected;
            this.raw = raw;
        }

        public boolean detected() {
            return detected;
        }
    }

    /** Expected identities must come from an independent, complete candidate discovery. */
    public List<Result> joinInventory(Collection<Identity> expected) {
        if (expected == null) {
            throw new IllegalArgumentException("pitInventoryMissing");
        }
        Set<Identity> admitted = new HashSet<>();
        for (Identity identity : expected) {
            admit(admitted, identity);
        }
        Map<Identity, Result> actual = new LinkedHashMap<>();
        for (Result result : results) {
            actual.put(result.identity(), result);
        }
        if (!actual.keySet().equals(admitted)) {
            throw new IllegalArgumentException("pitInventoryMismatch");
        }
        return expected.stream().map(actual::get).toList();
    }

    /**
     * The caller must bind inventory and replay proofs to the same fresh source snapshot.
     * XML killingTest text is only diagnostic; it never supplies typed assertion evidence.
     */
    public List<MutationRecord> normalize(Map<Identity, MutationCandidate> expected,
            Map<Identity, MutationProof> proofs) {
        if (expected == null || proofs == null) {
            throw new IllegalArgumentException("pitNormalizationInputMissing");
        }
        List<Result> joined = joinInventory(expected.keySet());
        if (!expected.keySet().containsAll(proofs.keySet())) {
            throw new IllegalArgumentException("pitProofUnexpected");
        }
        Set<String> candidateIds = new HashSet<>();
        List<MutationRecord> records = new ArrayList<>();
        for (Result result : joined) {
            MutationCandidate candidate = expected.get(result.identity());
            requireCandidate(candidate, result, candidateIds);
            records.add(normalizeOne(candidate, result, proofs.get(result.identity())));
        }
        return List.copyOf(records);
    }

    private static MutationRecord normalizeOne(
            MutationCandidate candidate, Result result, MutationProof proof) {
        if (result.status() == Status.KILLED && proof == null) {
            throw new IllegalArgumentException("pitProofMissing");
        }
        return new MutationRecord(candidate, MutationNormalizer.normalize(result.status().raw, proof));
    }

    private static void requireCandidate(MutationCandidate candidate, Result result, Set<String> seen) {
        if (candidate == null) {
            throw new IllegalArgumentException("pitCandidateMissing");
        }
        if (!seen.add(candidate.id())) {
            throw new IllegalArgumentException("pitCandidateDuplicate");
        }
        String file = java.nio.file.Path.of(candidate.relativePath()).getFileName().toString();
        if (candidate.line() != result.lineNumber() || !file.equals(result.sourceFile())) {
            throw new IllegalArgumentException("pitCandidateMismatch");
        }
    }

    private static void admit(Set<Identity> seen, Identity identity) {
        if (identity == null) {
            throw new IllegalArgumentException("pitIdentityMissing");
        }
        if (!seen.add(identity)) {
            throw new IllegalArgumentException("pitInventoryDuplicate");
        }
    }

    private static List<Integer> numbers(List<Integer> values) {
        if (values == null) {
            throw new IllegalArgumentException("pitNumberInvalid");
        }
        for (Integer value : values) {
            requireNumber(value);
        }
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException("pitNumberDuplicate");
        }
        return List.copyOf(values);
    }

    private static void requireNumber(Integer value) {
        if (value == null || value < 0) {
            throw new IllegalArgumentException("pitNumberInvalid");
        }
    }

    private static void requireClass(String value) {
        requireText(value);
        if (!SourceVersion.isName(value)) {
            throw new IllegalArgumentException("pitClassInvalid");
        }
    }

    private static void requireMethod(String method, String descriptor) {
        requireText(method);
        if (!SourceVersion.isIdentifier(method) && !method.equals("<init>") && !method.equals("<clinit>")) {
            throw new IllegalArgumentException("pitMethodInvalid");
        }
        try {
            MethodTypeDesc.ofDescriptor(descriptor);
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IllegalArgumentException("pitDescriptorInvalid");
        }
    }

    private static void requireSourceFile(String value) {
        requireText(value);
        if (value.contains("/") || value.contains("\\") || value.contains(":")
                || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("pitSourceFileInvalid");
        }
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("pitFieldInvalid");
        }
    }
}
