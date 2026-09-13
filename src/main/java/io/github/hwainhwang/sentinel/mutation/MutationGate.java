package io.github.hwainhwang.sentinel.mutation;

import io.github.hwainhwang.sentinel.crap.GateThreshold;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts exact candidate records into the shared killed-only evidence component. */
public final class MutationGate {
    private MutationGate() {
        throw new AssertionError("no instances");
    }

    public static Map<String, Object> component(List<MutationRecord> records) {
        return component(records, GateThreshold.DEFAULT_MUTATION_MIN);
    }

    /** At the default 100 percent this is exactly killed == inScope with every other state at zero. */
    public static Map<String, Object> component(
            List<MutationRecord> records, GateThreshold mutationMin) {
        if (records == null || mutationMin == null) {
            throw new IllegalArgumentException("mutationRecordsMissing");
        }
        Map<MutationState, Long> counts = new java.util.EnumMap<>(MutationState.class);
        for (MutationState state : MutationState.values()) {
            counts.put(state, 0L);
        }
        for (MutationRecord record : records) {
            if (record == null) {
                throw new IllegalArgumentException("mutationRecordMissing");
            }
            counts.put(record.state(), counts.get(record.state()) + 1L);
        }
        long inScope = records.size();
        boolean passed = inScope > 0
                && mutationMin.killRatePasses(counts.get(MutationState.KILLED), inScope);
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("inScope", inScope);
        component.put("killed", counts.get(MutationState.KILLED));
        component.put("survived", counts.get(MutationState.SURVIVED));
        component.put("uncovered", counts.get(MutationState.UNCOVERED));
        component.put("timedOut", counts.get(MutationState.TIMED_OUT));
        component.put("compileError", counts.get(MutationState.COMPILE_ERROR));
        component.put("runtimeError", counts.get(MutationState.RUNTIME_ERROR));
        component.put("pending", counts.get(MutationState.PENDING));
        component.put("ignored", counts.get(MutationState.IGNORED));
        component.put("toolError", counts.get(MutationState.TOOL_ERROR));
        component.put("unauthorizedExclusion", 0L);
        component.put("mutationMin", mutationMin.text());
        component.put("pass", passed);
        return Map.copyOf(component);
    }
}
