package io.github.hwainhwang.sentinel.evidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class EvidenceFixtures {
    static final String PROJECT_FILE_HEX =
            "7b22636c65616e75704c656173654b6579223a22494345694979516c4a69636f4b536f724c4330754c7a41784d6a4d304e5459334f446b364f7a7739506a38222c2266696e6765727072696e74486d61634b6579223a2241414543417751464267634943516f4c4441304f4478415245684d554652595847426b6147787764486838222c226b657945706f6368223a312c2270726f6a6563744964656e746966696572223a2241414543417751464267634943516f4c4441304f4477222c22736368656d6156657273696f6e223a2273656e74696e656c2d70726f6a6563742d73746174652d7631222c22737461746556657273696f6e223a2273746174652d7631227d0a";
    static final String CLEANUP_KEY_HEX =
            "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f";

    private EvidenceFixtures() {
        throw new AssertionError("no instances");
    }

    static Map<String, Object> strictCheckBody() {
        Map<String, Object> crap = map(
                "callableCount", 1,
                "maxNumerator", "8",
                "maxDenominator", "1",
                "pass", true,
                "unknownCount", 0);
        Map<String, Object> mutation = map(
                "inScope", 1,
                "killed", 1,
                "survived", 0,
                "uncovered", 0,
                "timedOut", 0,
                "compileError", 0,
                "runtimeError", 0,
                "pending", 0,
                "ignored", 0,
                "toolError", 0,
                "unauthorizedExclusion", 0,
                "pass", true);
        return map(
                "schemaVersion", "sentinel-evidence-v1",
                "specVersion", "1.0.0",
                "fingerprintVersion", "sentinel-fingerprint-v1",
                "correlationId", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "language", "python",
                "mode", "strict",
                "certification", true,
                "observationSource", "fresh",
                "sourceRunId", null,
                "keyEpoch", 1,
                "projectStateHmac", "441395de4352207dc696516a31efa8fb34fc5d4df9a9005537342cda21e84354",
                "startedSha256", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "diagnosticCodes", new ArrayList<>(),
                "runId", "11111111-1111-4111-8111-111111111111",
                "command", "check",
                "commitSequence", "1",
                "startedAtUtc", "2026-09-03T12:00:00Z",
                "completedAtUtc", "2026-09-03T12:00:01.1Z",
                "committedAtUtc", "2026-09-03T12:00:01.2Z",
                "terminalStatus", "passed",
                "exitCode", 0,
                "components", map("crap", crap, "mutation", mutation),
                "eventCount", 1,
                "events", List.of(map(
                        "filename", "00000000000000000000000000000001.json",
                        "sha256", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")));
    }

    static Map<String, Object> strictMutationFailureBody() {
        Map<String, Object> body = copy(strictCheckBody());
        body.put("certification", false);
        body.put("diagnosticCodes", List.of("survivedMutant"));
        body.put("runId", "22222222-2222-4222-8222-222222222222");
        body.put("command", "mutation");
        body.put("commitSequence", "2");
        body.put("startedAtUtc", "2026-09-03T13:00:00Z");
        body.put("completedAtUtc", "2026-09-03T13:00:01Z");
        body.put("committedAtUtc", "2026-09-03T13:00:02Z");
        body.put("terminalStatus", "qualityFailed");
        body.put("exitCode", 2);
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) body.get("components");
        @SuppressWarnings("unchecked")
        Map<String, Object> mutation = (Map<String, Object>) components.get("mutation");
        components.remove("crap");
        mutation.put("killed", 0);
        mutation.put("survived", 1);
        mutation.put("pass", false);
        body.put("events", List.of(map(
                "filename", "00000000000000000000000000000001.json",
                "sha256", "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")));
        return body;
    }

    static Map<String, Object> localCacheCrapBody() {
        Map<String, Object> body = copy(strictCheckBody());
        body.put("mode", "local");
        body.put("certification", false);
        body.put("observationSource", "cache");
        body.put("sourceRunId", "11111111-1111-4111-8111-111111111111");
        body.put("runId", "33333333-3333-4333-8333-333333333333");
        body.put("command", "crap");
        body.put("commitSequence", "3");
        body.put("startedAtUtc", "2026-09-03T14:00:00Z");
        body.put("completedAtUtc", "2026-09-03T14:00:01Z");
        body.put("committedAtUtc", "2026-09-03T14:00:02Z");
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) body.get("components");
        components.remove("mutation");
        body.put("eventCount", 0);
        body.put("events", List.of());
        return body;
    }

    static Map<String, Object> copy(Map<String, Object> source) {
        @SuppressWarnings("unchecked")
        Map<String, Object> copy = (Map<String, Object>) deepCopy(source);
        return copy;
    }

    static ProjectState rotatedProject() {
        Map<String, Object> document = ProjectState.parse(
                java.util.HexFormat.of().parseHex(PROJECT_FILE_HEX)).document();
        document.put(
                "fingerprintHmacKey",
                "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8");
        document.put("keyEpoch", 2);
        return ProjectState.from(document);
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, item) -> result.put((String) key, deepCopy(item)));
            return result;
        }
        if (value instanceof List<?> source) {
            return source.stream().map(EvidenceFixtures::deepCopy).toList();
        }
        return value;
    }

    static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }
}
