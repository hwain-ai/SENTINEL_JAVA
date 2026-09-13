package io.github.hwainhwang.sentinel.evidence;

import io.github.hwainhwang.sentinel.crap.GateThreshold;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Authenticates and semantically validates one terminal SENTINEL evidence record. */
public final class EvidenceContract {
    private static final String KEY_DOMAIN = "SENTINEL\0evidence-key\0v1\0";
    private static final String MAC_DOMAIN = "SENTINEL\0evidence\0v1\0";
    private static final Set<String> BODY_FIELDS = Set.of(
            "certification", "command", "commitSequence", "committedAtUtc",
            "completedAtUtc", "components", "correlationId", "diagnosticCodes",
            "eventCount", "events", "exitCode", "fingerprintVersion", "keyEpoch",
            "language", "mode", "observationSource", "projectStateHmac", "runId",
            "schemaVersion", "sourceRunId", "specVersion", "startedAtUtc",
            "startedSha256", "terminalStatus");
    private static final Set<String> CRAP_FIELDS = Set.of(
            "callableCount", "crapMax", "maxNumerator", "maxDenominator", "pass",
            "unknownCount");
    private static final List<String> MUTATION_STATES = List.of(
            "killed", "survived", "uncovered", "timedOut", "compileError",
            "runtimeError", "pending", "ignored", "toolError");
    private static final Set<String> MUTATION_FIELDS = Set.of(
            "inScope", "killed", "survived", "uncovered", "timedOut",
            "compileError", "runtimeError", "pending", "ignored", "toolError",
            "unauthorizedExclusion", "mutationMin", "pass");
    private static final Set<String> COMMANDS = Set.of("crap", "mutation", "check");
    private static final Set<String> LANGUAGES = Set.of(
            "python", "typescript", "go", "java", "clojure");
    private static final Set<String> MODES = Set.of("strict", "local");
    private static final Set<String> SOURCES = Set.of("fresh", "cache");
    private static final Pattern UUID_TEXT = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern SEMVER = Pattern.compile(
            "^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?$");
    private static final Pattern SAFE_CODE = Pattern.compile("^[a-z][A-Za-z0-9]{0,63}$");
    private static final Pattern EVENT_FILENAME = Pattern.compile("^[0-9a-f]{32}\\.json$");
    private static final Pattern PUBLIC_FINGERPRINT = Pattern.compile(
            "^hmac-sha256:[0-9a-f]{64}$");
    private static final Pattern UTC = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.([0-9]{1,9}))?Z$");
    private static final Map<String, Long> TERMINAL_EXITS = terminalExits();

    private EvidenceContract() {
        throw new AssertionError("no instances");
    }

    public static byte[] build(Map<String, Object> body, ProjectState projectState) {
        Map<String, Object> validated = ContractValues.map(body, "evidenceFieldsInvalid");
        validateBody(validated, projectState, false);
        Map<String, Object> record = new LinkedHashMap<>(validated);
        record.put("hmacSha256", EvidenceMac.record(
                projectState.cleanupLeaseKey(), KEY_DOMAIN, MAC_DOMAIN, validated));
        return CanonicalJson.file(record);
    }

    public static Map<String, Object> validate(byte[] payload, ProjectState projectState) {
        Map<String, Object> document = ContractValues.map(
                CanonicalJson.readFile(payload), "evidenceFieldsInvalid");
        Set<String> expected = new HashSet<>(BODY_FIELDS);
        expected.add("hmacSha256");
        ContractValues.exactFields(document, expected, "evidenceFieldsInvalid");
        String actual = ContractValues.hex256(document.get("hmacSha256"), "evidenceHmacInvalid");
        Map<String, Object> body = new LinkedHashMap<>(document);
        body.remove("hmacSha256");
        validateBody(body, projectState, true);
        String expectedHmac = EvidenceMac.record(
                projectState.cleanupLeaseKey(), KEY_DOMAIN, MAC_DOMAIN, body);
        if (!EvidenceMac.matches(actual, expectedHmac)) {
            throw new EvidenceContractException("evidenceHmacMismatch");
        }
        return document;
    }

    public static List<Map<String, String>> assignEventOrdinals(List<String> fingerprints) {
        if (fingerprints == null) {
            throw new EvidenceContractException("findingFingerprintsInvalid");
        }
        List<String> ordered = validateFingerprints(fingerprints);
        ordered.sort(CanonicalJson::compareUtf8);
        List<Map<String, String>> result = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            String eventId = String.format("%032x", index + 1L);
            result.add(Map.of(
                    "eventId", eventId,
                    "filename", eventId + ".json",
                    "fingerprint", ordered.get(index)));
        }
        return List.copyOf(result);
    }

    private static List<String> validateFingerprints(List<String> fingerprints) {
        List<String> result = new ArrayList<>();
        for (Object value : fingerprints) {
            result.add(requirePattern(
                    value, PUBLIC_FINGERPRINT, "findingFingerprintInvalid"));
        }
        if (new HashSet<>(result).size() != result.size()) {
            throw new EvidenceContractException("findingFingerprintDuplicate");
        }
        return result;
    }

    private static void validateBody(
            Map<String, Object> body, ProjectState projectState, boolean allowHistoricalEpoch) {
        if (projectState == null) {
            throw new EvidenceContractException("projectStateFieldsInvalid");
        }
        ContractValues.exactFields(body, BODY_FIELDS, "evidenceFieldsInvalid");
        validateIdentity(body);
        validateTimes(body);
        boolean componentsPass = validateComponents(body);
        validateTerminal(body, componentsPass);
        validateManifest(body);
        validateDiagnostics(body.get("diagnosticCodes"));
        validateProjectBinding(body, projectState, allowHistoricalEpoch);
    }

    private static void validateIdentity(Map<String, Object> body) {
        requireEqual(body.get("schemaVersion"), "sentinel-evidence-v1", "evidenceSchemaVersionInvalid");
        requirePattern(body.get("specVersion"), SEMVER, "specVersionInvalid");
        requireEqual(body.get("fingerprintVersion"), "sentinel-fingerprint-v1", "fingerprintVersionInvalid");
        uuid(body.get("runId"), "runIdInvalid");
        uuid(body.get("correlationId"), "correlationIdInvalid");
        requireMember(body.get("command"), COMMANDS, "commandInvalid");
        requireMember(body.get("language"), LANGUAGES, "languageInvalid");
        requireMember(body.get("mode"), MODES, "modeInvalid");
        requireMember(body.get("observationSource"), SOURCES, "observationSourceInvalid");
        ContractValues.uint64(body.get("commitSequence"), false, "commitSequenceInvalid");
        ContractValues.safeInteger(body.get("keyEpoch"), true, "keyEpochInvalid");
        ContractValues.hex256(body.get("projectStateHmac"), "projectStateHmacInvalid");
        ContractValues.hex256(body.get("startedSha256"), "startedSha256Invalid");
        validateSourceRun(body);
        if ("strict".equals(body.get("mode")) && "cache".equals(body.get("observationSource"))) {
            throw new EvidenceContractException("strictCacheInvalid");
        }
    }

    private static void validateSourceRun(Map<String, Object> body) {
        Object sourceRunId = body.get("sourceRunId");
        if ("fresh".equals(body.get("observationSource"))) {
            if (sourceRunId != null) {
                throw new EvidenceContractException("sourceRunIdInvalid");
            }
            return;
        }
        String source = uuid(sourceRunId, "sourceRunIdInvalid");
        if (source.equals(body.get("runId"))) {
            throw new EvidenceContractException("sourceRunIdInvalid");
        }
    }

    private static String uuid(Object value, String code) {
        String text = ContractValues.string(value, code);
        if (!UUID_TEXT.matcher(text).matches()) {
            throw new EvidenceContractException(code);
        }
        try {
            if (!UUID.fromString(text).toString().equals(text)) {
                throw new EvidenceContractException(code);
            }
        } catch (IllegalArgumentException error) {
            throw new EvidenceContractException(code, error);
        }
        return text;
    }

    private static void validateTimes(Map<String, Object> body) {
        LocalDateTime started = timestamp(body.get("startedAtUtc"));
        LocalDateTime completed = timestamp(body.get("completedAtUtc"));
        LocalDateTime committed = timestamp(body.get("committedAtUtc"));
        if (started.isAfter(completed) || completed.isAfter(committed)) {
            throw new EvidenceContractException("evidenceTimeOrderInvalid");
        }
    }

    private static LocalDateTime timestamp(Object value) {
        String text = ContractValues.string(value, "utcTimestampInvalid");
        Matcher match = UTC.matcher(text);
        if (!match.matches() || hasTrailingFractionZero(match.group(7))) {
            throw new EvidenceContractException("utcTimestampInvalid");
        }
        try {
            return timestampParts(match);
        } catch (DateTimeException | NumberFormatException error) {
            throw new EvidenceContractException("utcTimestampInvalid", error);
        }
    }

    private static boolean hasTrailingFractionZero(String fraction) {
        return fraction != null && fraction.endsWith("0");
    }

    private static LocalDateTime timestampParts(Matcher match) {
        int year = Integer.parseInt(match.group(1));
        if (year == 0) {
            throw new DateTimeException("year zero");
        }
        String fraction = match.group(7);
        int nanos = fraction == null ? 0 : Integer.parseInt((fraction + "000000000").substring(0, 9));
        return LocalDateTime.of(
                year,
                Integer.parseInt(match.group(2)),
                Integer.parseInt(match.group(3)),
                Integer.parseInt(match.group(4)),
                Integer.parseInt(match.group(5)),
                Integer.parseInt(match.group(6)),
                nanos);
    }

    private static boolean validateComponents(Map<String, Object> body) {
        String command = (String) body.get("command");
        Map<String, Object> components = ContractValues.map(
                body.get("components"), "evidenceComponentsInvalid");
        Set<String> required = "check".equals(command)
                ? Set.of("crap", "mutation") : Set.of(command);
        ContractValues.exactFields(components, required, "evidenceComponentsInvalid");
        List<Boolean> passes = new ArrayList<>();
        if (components.containsKey("crap")) {
            passes.add(validateCrap(components.get("crap")));
        }
        if (components.containsKey("mutation")) {
            passes.add(validateMutation(components.get("mutation")));
        }
        return passes.stream().allMatch(Boolean::booleanValue);
    }

    private static boolean validateCrap(Object value) {
        Map<String, Object> component = ContractValues.map(value, "crapComponentInvalid");
        ContractValues.exactFields(component, CRAP_FIELDS, "crapComponentInvalid");
        BigInteger numerator = ContractValues.decimal(
                component.get("maxNumerator"), true, 96, "crapComponentInvalid");
        BigInteger denominator = ContractValues.decimal(
                component.get("maxDenominator"), false, 48, "crapComponentInvalid");
        if (!numerator.gcd(denominator).equals(BigInteger.ONE)) {
            throw new EvidenceContractException("crapComponentFractionInvalid");
        }
        long unknown = ContractValues.safeInteger(
                component.get("unknownCount"), false, "crapComponentInvalid");
        long callableCount = ContractValues.safeInteger(
                component.get("callableCount"), false, "crapComponentInvalid");
        if (unknown > callableCount) {
            throw new EvidenceContractException("crapComponentInvalid");
        }
        boolean actual = ContractValues.bool(component.get("pass"), "crapComponentInvalid");
        GateThreshold crapMax = threshold(component.get("crapMax"), true, "crapComponentInvalid");
        boolean expected = callableCount > 0 && unknown == 0
                && crapMax.crapPasses(numerator, denominator);
        if (actual != expected) {
            throw new EvidenceContractException("crapComponentSemanticsInvalid");
        }
        return actual;
    }

    private static boolean validateMutation(Object value) {
        Map<String, Object> component = ContractValues.map(value, "mutationComponentInvalid");
        ContractValues.exactFields(component, MUTATION_FIELDS, "mutationComponentInvalid");
        Map<String, Long> counts = mutationCounts(component);
        long inScope = ContractValues.safeInteger(
                component.get("inScope"), false, "mutationComponentInvalid");
        long unauthorized = ContractValues.safeInteger(
                component.get("unauthorizedExclusion"), false, "mutationComponentInvalid");
        boolean actual = ContractValues.bool(component.get("pass"), "mutationComponentInvalid");
        if (counts.values().stream().mapToLong(Long::longValue).sum() != inScope) {
            throw new EvidenceContractException("mutationComponentInvalid");
        }
        GateThreshold mutationMin = threshold(
                component.get("mutationMin"), false, "mutationComponentInvalid");
        boolean expected = mutationPasses(counts, inScope, unauthorized, mutationMin);
        if (actual != expected) {
            throw new EvidenceContractException("mutationComponentSemanticsInvalid");
        }
        return actual;
    }

    private static Map<String, Long> mutationCounts(Map<String, Object> component) {
        Map<String, Long> result = new HashMap<>();
        for (String state : MUTATION_STATES) {
            result.put(state, ContractValues.safeInteger(
                    component.get(state), false, "mutationComponentInvalid"));
        }
        return result;
    }

    /** At the default 100 percent this is exactly killed == inScope with every other state at zero. */
    private static boolean mutationPasses(
            Map<String, Long> counts, long inScope, long unauthorized, GateThreshold mutationMin) {
        return inScope >= 1 && unauthorized == 0
                && mutationMin.killRatePasses(counts.get("killed"), inScope);
    }

    private static GateThreshold threshold(Object value, boolean crap, String code) {
        if (!(value instanceof String text)) {
            throw new EvidenceContractException(code);
        }
        try {
            return crap ? GateThreshold.crapMax(text) : GateThreshold.mutationMin(text);
        } catch (IllegalArgumentException failure) {
            throw new EvidenceContractException(code);
        }
    }

    private static void validateTerminal(Map<String, Object> body, boolean componentsPass) {
        String status = ContractValues.string(body.get("terminalStatus"), "terminalStatusExitCodeMismatch");
        validateExitCode(body, status);
        validateToolErrorPrecedence(body, status);
        validateTerminalComponents(status, componentsPass);
        validateCertification(body, status, componentsPass);
    }

    private static void validateToolErrorPrecedence(Map<String, Object> body, String status) {
        Map<String, Object> components = ContractValues.map(
                body.get("components"), "evidenceComponentsInvalid");
        if (!components.containsKey("mutation")) {
            return;
        }
        Map<String, Object> mutation = ContractValues.map(
                components.get("mutation"), "mutationComponentInvalid");
        long toolErrors = ContractValues.safeInteger(
                mutation.get("toolError"), false, "mutationComponentInvalid");
        if (toolErrors > 0 && !"backendError".equals(status)) {
            throw new EvidenceContractException("terminalStatusPrecedenceInvalid");
        }
    }

    private static void validateExitCode(Map<String, Object> body, String status) {
        Long expectedExit = TERMINAL_EXITS.get(status);
        long actualExit = ContractValues.safeInteger(
                body.get("exitCode"), false, "terminalStatusExitCodeMismatch");
        if (expectedExit == null || actualExit != expectedExit) {
            throw new EvidenceContractException("terminalStatusExitCodeMismatch");
        }
    }

    private static void validateTerminalComponents(String status, boolean componentsPass) {
        if ("passed".equals(status) && !componentsPass
                || "qualityFailed".equals(status) && componentsPass) {
            throw new EvidenceContractException("terminalComponentMismatch");
        }
    }

    private static void validateCertification(
            Map<String, Object> body, String status, boolean componentsPass) {
        boolean actualCertification = ContractValues.bool(
                body.get("certification"), "certificationInvalid");
        boolean expectedCertification = "strict".equals(body.get("mode"))
                && "fresh".equals(body.get("observationSource"))
                && "passed".equals(status) && componentsPass;
        if (actualCertification != expectedCertification) {
            throw new EvidenceContractException("certificationInvalid");
        }
    }

    private static void validateManifest(Map<String, Object> body) {
        long count = ContractValues.safeInteger(
                body.get("eventCount"), false, "eventManifestInvalid");
        List<?> events = ContractValues.list(body.get("events"), "eventManifestInvalid");
        if (count != events.size()) {
            throw new EvidenceContractException("eventManifestCountMismatch");
        }
        List<String> names = manifestNames(events);
        if (new HashSet<>(names).size() != names.size()) {
            throw new EvidenceContractException("eventManifestDuplicate");
        }
        requireManifestOrdinals(names);
        if ("cache".equals(body.get("observationSource")) && !events.isEmpty()) {
            throw new EvidenceContractException("cacheObservationHasEvents");
        }
    }

    private static List<String> manifestNames(List<?> events) {
        List<String> result = new ArrayList<>();
        for (Object event : events) {
            Map<String, Object> entry = ContractValues.map(event, "eventManifestInvalid");
            ContractValues.exactFields(entry, Set.of("filename", "sha256"), "eventManifestInvalid");
            String filename = requirePattern(
                    entry.get("filename"), EVENT_FILENAME, "eventManifestFilenameInvalid");
            ContractValues.hex256(entry.get("sha256"), "eventManifestDigestInvalid");
            result.add(filename);
        }
        return result;
    }

    private static void requireManifestOrdinals(List<String> names) {
        for (int index = 0; index < names.size(); index++) {
            String expected = String.format("%032x.json", index + 1L);
            if (!expected.equals(names.get(index))) {
                throw new EvidenceContractException("eventManifestOrdinalInvalid");
            }
        }
    }

    private static void validateDiagnostics(Object value) {
        List<?> diagnostics = ContractValues.list(value, "diagnosticCodesInvalid");
        List<String> codes = new ArrayList<>();
        for (Object item : diagnostics) {
            codes.add(requirePattern(item, SAFE_CODE, "diagnosticCodesInvalid"));
        }
        List<String> sorted = codes.stream().sorted(CanonicalJson::compareUtf8).toList();
        if (new HashSet<>(codes).size() != codes.size() || !codes.equals(sorted)) {
            throw new EvidenceContractException("diagnosticCodesInvalid");
        }
    }

    private static void validateProjectBinding(
            Map<String, Object> body,
            ProjectState projectState,
            boolean allowHistoricalEpoch) {
        long epoch = ContractValues.safeInteger(body.get("keyEpoch"), true, "keyEpochInvalid");
        if (epoch > projectState.keyEpoch()
                || !allowHistoricalEpoch && epoch != projectState.keyEpoch()) {
            throw new EvidenceContractException("keyEpochInvalid");
        }
        String binding = (String) body.get("projectStateHmac");
        if (!EvidenceMac.matches(binding, projectState.bindingHmac())) {
            throw new EvidenceContractException("projectStateBindingInvalid");
        }
    }

    private static String requirePattern(Object value, Pattern pattern, String code) {
        String text = ContractValues.string(value, code);
        if (!pattern.matcher(text).matches()) {
            throw new EvidenceContractException(code);
        }
        return text;
    }

    private static void requireEqual(Object value, String expected, String code) {
        if (!expected.equals(value)) {
            throw new EvidenceContractException(code);
        }
    }

    private static void requireMember(Object value, Set<String> expected, String code) {
        String text = ContractValues.string(value, code);
        if (!expected.contains(text)) {
            throw new EvidenceContractException(code);
        }
    }

    private static Map<String, Long> terminalExits() {
        Map<String, Long> result = new HashMap<>();
        result.put("passed", 0L);
        result.put("toolError", 1L);
        result.put("qualityFailed", 2L);
        result.put("baselineFailed", 4L);
        result.put("dependencyError", 5L);
        result.put("backendError", 6L);
        result.put("evidenceError", 7L);
        result.put("cancelled", 8L);
        return Map.copyOf(result);
    }
}
