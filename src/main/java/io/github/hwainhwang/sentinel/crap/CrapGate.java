package io.github.hwainhwang.sentinel.crap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Path;

/** Applies the exact CRAP threshold to every production callable. */
public final class CrapGate {
    private CrapGate() {
        throw new AssertionError("no instances");
    }

    public static Result evaluate(Map<String, byte[]> sources, byte[] coverageXml) {
        return evaluate(sources, coverageXml, List.of());
    }

    public static Result evaluate(
            Map<String, byte[]> sources, byte[] coverageXml, List<Path> dependencyClasspath) {
        return evaluate(sources, coverageXml, dependencyClasspath, GateThreshold.DEFAULT_CRAP_MAX);
    }

    public static Result evaluate(
            Map<String, byte[]> sources,
            byte[] coverageXml,
            List<Path> dependencyClasspath,
            GateThreshold crapMax) {
        return evaluate(sources, coverageXml, dependencyClasspath, crapMax, null);
    }

    /**
     * Every source is analyzed (semantic analysis needs the whole tree) but only callables whose
     * module-relative path is in {@code onlyPaths} are judged; {@code null} judges all of them.
     */
    public static Result evaluate(
            Map<String, byte[]> sources,
            byte[] coverageXml,
            List<Path> dependencyClasspath,
            GateThreshold crapMax,
            Set<String> onlyPaths) {
        List<Models.CallableDefinition> definitions = JavaAnalyzer.analyzeAll(
                sources, dependencyClasspath);
        JacocoCoverage.Report report = JacocoCoverage.parse(coverageXml);
        List<Models.CallableMetric> metrics = JacocoCoverage.measure(definitions, report, crapMax);
        return result(onlyPaths == null ? metrics : judged(metrics, onlyPaths));
    }

    private static List<Models.CallableMetric> judged(
            List<Models.CallableMetric> metrics, Set<String> onlyPaths) {
        List<Models.CallableMetric> judged = new ArrayList<>();
        for (Models.CallableMetric metric : metrics) {
            if (onlyPaths.contains(metric.callable().identity().moduleRelativePath())) {
                judged.add(metric);
            }
        }
        return List.copyOf(judged);
    }

    private static Result result(List<Models.CallableMetric> metrics) {
        List<Models.CrapRow> rows = new ArrayList<>(metrics.size());
        int known = 0;
        int aboveLimit = 0;
        for (Models.CallableMetric metric : metrics) {
            rows.add(row(metric));
            if (metric.known()) {
                known++;
                if (!metric.crap().passed()) {
                    aboveLimit++;
                }
            }
        }
        int unknown = metrics.size() - known;
        boolean passed = !metrics.isEmpty() && unknown == 0 && aboveLimit == 0;
        return new Result(metrics.size(), known, unknown, aboveLimit, passed, CrapRows.sort(rows));
    }

    private static Models.CrapRow row(Models.CallableMetric metric) {
        Models.CallableDefinition callable = metric.callable();
        String path = callable.identity().moduleRelativePath();
        long start = callable.sourceRange().startByte();
        String id = callable.identity().callableId();
        if (!metric.known()) {
            return Models.CrapRow.unknown(path, start, id, metric.unknownReason().name());
        }
        return Models.CrapRow.known(
                path,
                start,
                id,
                metric.crap().numerator(),
                metric.crap().denominator());
    }

    public record Result(
            int total,
            int known,
            int unknown,
            int aboveLimit,
            boolean passed,
            List<Models.CrapRow> rows) {
        public Result {
            rows = List.copyOf(rows);
            validateCounts(total, known, unknown, aboveLimit, rows.size());
            validateVerdict(total, unknown, aboveLimit, passed);
        }
    }

    private static void validateCounts(
            int total, int known, int unknown, int aboveLimit, int rowCount) {
        validateNonnegativeCounts(total, known, unknown, aboveLimit);
        validateCountRelationships(total, known, unknown, aboveLimit, rowCount);
    }

    private static void validateNonnegativeCounts(
            int total, int known, int unknown, int aboveLimit) {
        if (total < 0 || known < 0 || unknown < 0 || aboveLimit < 0) {
            throw new IllegalArgumentException("crapGateResultInvalid");
        }
    }

    private static void validateCountRelationships(
            int total, int known, int unknown, int aboveLimit, int rowCount) {
        if (known + unknown != total || aboveLimit > known || rowCount != total) {
            throw new IllegalArgumentException("crapGateResultInvalid");
        }
    }

    private static void validateVerdict(
            int total, int unknown, int aboveLimit, boolean passed) {
        if (passed != (total > 0 && unknown == 0 && aboveLimit == 0)) {
            throw new IllegalArgumentException("crapGateVerdictInvalid");
        }
    }
}
