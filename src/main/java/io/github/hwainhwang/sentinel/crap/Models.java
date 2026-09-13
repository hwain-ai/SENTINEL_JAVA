package io.github.hwainhwang.sentinel.crap;

import java.math.BigInteger;
import java.util.Objects;

/** Immutable values shared by source analysis, coverage joining, and report sorting. */
public final class Models {
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private Models() {
        throw new AssertionError("no instances");
    }

    public enum CallableKind {
        METHOD,
        CONSTRUCTOR,
        LAMBDA
    }

    public enum CoverageUnknownReason {
        REPORT_MISSING,
        METHOD_MISSING,
        METHOD_AMBIGUOUS,
        COUNTER_AMBIGUOUS,
        ZERO_INSTRUCTIONS,
        CLASSFILE_MAPPING_UNAVAILABLE,
        LAMBDA_MAPPING_UNAVAILABLE
    }

    public record SourceRange(long startByte, long endByte) {
        public SourceRange {
            if (startByte < 0 || endByte <= startByte || endByte > MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException("sourceRangeInvalid");
            }
        }
    }

    public record CallableIdentity(
            String moduleRelativePath,
            CallableKind kind,
            String owner,
            String callableName,
            String descriptor,
            String semanticSite) {
        public CallableIdentity {
            validateModulePath(moduleRelativePath);
            Objects.requireNonNull(kind, "kind");
            requireText(owner, "owner");
            requireText(callableName, "callableName");
            requireText(descriptor, "descriptor");
            if (kind == CallableKind.LAMBDA) {
                requireText(semanticSite, "semanticSite");
            } else if (semanticSite != null) {
                throw new IllegalArgumentException("semanticSiteUnexpected");
            }
        }

        public String callableId() {
            return SemanticSite.callableId(
                    moduleRelativePath,
                    kind.name(),
                    owner,
                    callableName,
                    descriptor,
                    semanticSite);
        }
    }

    public record CallableDefinition(
            CallableIdentity identity,
            SourceRange sourceRange,
            long declarationLine,
            long sourceEndLine,
            int complexity,
            String jacocoClassName,
            String jacocoMethodName) {
        public CallableDefinition {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(sourceRange, "sourceRange");
            validateCallableLines(declarationLine, sourceEndLine);
            validateComplexity(complexity);
            validateJacocoIdentity(jacocoClassName, jacocoMethodName);
        }
    }

    private static void validateCallableLines(long declarationLine, long sourceEndLine) {
            if (declarationLine < 1 || declarationLine > MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException("declarationLineOutOfRange");
            }
            if (sourceEndLine < declarationLine || sourceEndLine > MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException("sourceEndLineOutOfRange");
            }
    }

    private static void validateComplexity(int complexity) {
        if (complexity < 1) {
            throw new IllegalArgumentException("complexityOutOfRange");
        }
    }

    private static void validateJacocoIdentity(String jacocoClassName, String jacocoMethodName) {
            if ((jacocoClassName == null) != (jacocoMethodName == null)) {
                throw new IllegalArgumentException("jacocoIdentityIncomplete");
            }
            if (jacocoClassName != null) {
                requireText(jacocoClassName, "jacocoClassName");
                requireText(jacocoMethodName, "jacocoMethodName");
            }
    }

    public record CallableMetric(
            CallableDefinition callable,
            long coveredUnits,
            long totalUnits,
            ExactCrap crap,
            CoverageUnknownReason unknownReason) {
        public CallableMetric {
            Objects.requireNonNull(callable, "callable");
            if ((crap == null) == (unknownReason == null)) {
                throw new IllegalArgumentException("coverageKnownUnknownInvalid");
            }
            validateMetricCounts(crap, coveredUnits, totalUnits);
        }

        public static CallableMetric known(
                CallableDefinition callable, long coveredUnits, long totalUnits) {
            return known(callable, coveredUnits, totalUnits, GateThreshold.DEFAULT_CRAP_MAX);
        }

        public static CallableMetric known(
                CallableDefinition callable,
                long coveredUnits,
                long totalUnits,
                GateThreshold crapMax) {
            ExactCrap score = ExactCrap.calculate(
                    callable.complexity(), coveredUnits, totalUnits, crapMax);
            return new CallableMetric(callable, coveredUnits, totalUnits, score, null);
        }

        public static CallableMetric unknown(
                CallableDefinition callable, CoverageUnknownReason reason) {
            return new CallableMetric(callable, 0, 0, null, Objects.requireNonNull(reason));
        }

        public boolean known() {
            return crap != null;
        }
    }

    public record CrapRow(
            String moduleRelativePath,
            long sourceStartByte,
            String callableId,
            BigInteger numerator,
            BigInteger denominator,
            String unknownReason) {
        public CrapRow {
            validateModulePath(moduleRelativePath);
            if (sourceStartByte < 0 || sourceStartByte > MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException("sourceStartByteInvalid");
            }
            requireText(callableId, "callableId");
            boolean known = numerator != null || denominator != null;
            if (known) {
                validateKnownFraction(numerator, denominator, unknownReason);
            } else {
                requireText(unknownReason, "unknownReason");
            }
        }

        public static CrapRow known(
                String path, long start, String callableId, BigInteger numerator, BigInteger denominator) {
            return new CrapRow(path, start, callableId, numerator, denominator, null);
        }

        public static CrapRow unknown(String path, long start, String callableId, String reason) {
            return new CrapRow(path, start, callableId, null, null, reason);
        }

        public boolean known() {
            return numerator != null;
        }
    }

    public static void validateModulePath(String path) {
        requireText(path, "moduleRelativePath");
        if (invalidPathShape(path)) {
            throw new IllegalArgumentException("moduleRelativePathInvalid");
        }
        validateSegments(path);
    }

    private static boolean invalidPathShape(String path) {
        return path.startsWith("/")
                || path.startsWith("./")
                || path.endsWith("/")
                || path.contains("\\")
                || path.indexOf('\0') >= 0;
    }

    private static void validateSegments(String path) {
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("moduleRelativePathInvalid");
            }
        }
    }

    private static void validateMetricCounts(
            ExactCrap crap, long coveredUnits, long totalUnits) {
        if (crap == null) {
            validateUnknownCounts(coveredUnits, totalUnits);
            return;
        }
        if (coveredUnits < 0 || coveredUnits > MAX_SAFE_INTEGER
                || totalUnits < 1 || totalUnits > MAX_SAFE_INTEGER
                || coveredUnits > totalUnits) {
            throw new IllegalArgumentException("coverageCountsInvalid");
        }
    }

    private static void validateUnknownCounts(long coveredUnits, long totalUnits) {
        if (coveredUnits != 0 || totalUnits != 0) {
            throw new IllegalArgumentException("unknownCoverageHasCounts");
        }
    }

    private static void validateKnownFraction(
            BigInteger numerator, BigInteger denominator, String unknownReason) {
        if (numerator == null || denominator == null || unknownReason != null) {
            throw new IllegalArgumentException("knownUnknownInvalid");
        }
        if (numerator.signum() < 0 || denominator.signum() <= 0) {
            throw new IllegalArgumentException("crapFractionInvalid");
        }
        if (!numerator.gcd(denominator).equals(BigInteger.ONE)) {
            throw new IllegalArgumentException("crapFractionNotReduced");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + "Missing");
        }
        SemanticSite.utf8(value);
    }
}
