package io.github.hwainhwang.sentinel.mutation.junit;

import io.github.hwainhwang.sentinel.mutation.ExecutionStatus;
import io.github.hwainhwang.sentinel.mutation.MutationHash;
import io.github.hwainhwang.sentinel.mutation.MutationNormalizer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.opentest4j.MultipleFailuresError;

/** Opt-in JUnit Platform listener that emits one typed, machine-readable execution summary. */
public final class SentinelTestExecutionListener implements TestExecutionListener {
    private JUnitRequest request;
    private TestPlan plan;
    private final Set<String> expected = new HashSet<>();
    private final Map<String, Integer> starts = new HashMap<>();
    private final Map<String, Integer> finishes = new HashMap<>();
    // Tests JUnit skipped (disabled or conditionally excluded): they never start, and every run
    // must skip the same set, so they are part of the inventory instead of a failure.
    private final Map<String, Integer> skipped = new HashMap<>();
    private final List<Failure> assertionFailures = new ArrayList<>();
    private final List<String> results = new ArrayList<>();
    private boolean runtimeFailure;
    private Summary completed;

    @Override
    public synchronized void testPlanExecutionStarted(TestPlan testPlan) {
        try {
            request = JUnitRequest.readIfPresent();
        } catch (IOException error) {
            throw new IllegalStateException("junitRequestUnreadable", error);
        }
        if (request == null) {
            return;
        }
        plan = testPlan;
        collectExpected(testPlan);
    }

    private void collectExpected(TestPlan plan) {
        for (TestIdentifier root : plan.getRoots()) {
            addIfTest(root);
            for (TestIdentifier identifier : plan.getDescendants(root)) {
                addIfTest(identifier);
            }
        }
    }

    private void addIfTest(TestIdentifier identifier) {
        if (identifier.isTest() && !expected.add(identifier.getUniqueId())) {
            runtimeFailure = true;
        }
    }

    @Override
    public synchronized void dynamicTestRegistered(TestIdentifier identifier) {
        // Parameterized and dynamic tests register after the plan starts; their ids join the inventory,
        // so both control runs must generate the same set.
        if (request != null) {
            addIfTest(identifier);
        }
    }

    @Override
    public synchronized void executionSkipped(TestIdentifier identifier, String reason) {
        if (request == null) {
            return;
        }
        results.add(MutationHash.digest("sentinel-java-test-result-v1", List.of(identifier.getUniqueId(), "SKIPPED")));
        if (identifier.isTest()) {
            increment(skipped, identifier.getUniqueId());
            return;
        }
        // A skipped container (a disabled class) silently skips every test under it.
        for (TestIdentifier descendant : plan.getDescendants(identifier)) {
            if (descendant.isTest()) {
                increment(skipped, descendant.getUniqueId());
            }
        }
    }

    @Override
    public synchronized void executionStarted(TestIdentifier identifier) {
        if (request != null && identifier.isTest()) {
            increment(starts, identifier.getUniqueId());
        }
    }

    @Override
    public synchronized void executionFinished(
            TestIdentifier identifier, TestExecutionResult result) {
        if (request == null) {
            return;
        }
        results.add(MutationHash.digest("sentinel-java-test-result-v1",
                List.of(identifier.getUniqueId(), result.getStatus().name())));
        if (identifier.isTest()) {
            finishTest(identifier, result);
        } else if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
            runtimeFailure = true;
        }
    }

    private void finishTest(TestIdentifier identifier, TestExecutionResult result) {
        String id = identifier.getUniqueId();
        increment(finishes, id);
        if (result.getStatus() == TestExecutionResult.Status.SUCCESSFUL) {
            return;
        }
        boolean recorded = false;
        try {
            recorded = recordAssertion(identifier, result.getThrowable().orElse(null));
        } finally {
            // JUnit may swallow listener callback errors; incomplete inspection must remain an error.
            if (!recorded) {
                runtimeFailure = true;
            }
        }
    }

    private boolean recordAssertion(TestIdentifier identifier, Throwable failure) {
        if (failure == null || classify(failure) != ExecutionStatus.ASSERTION_FAILURE) {
            return false;
        }
        String site = failureSite(identifier, failure);
        if (site.isEmpty()) {
            return false;
        }
        assertionFailures.add(new Failure(identifier.getUniqueId(), failure.getClass().getName(), safeMessage(failure), site));
        return true;
    }

    // RISK(security): stack locations distinguish equal messages, but are not a hostile-code sandbox.
    private static String failureSite(TestIdentifier identifier, Throwable failure) {
        if (!(identifier.getSource().orElse(null) instanceof MethodSource source)) {
            return "";
        }
        return failureSite(source, failure, 0);
    }

    private static String failureSite(MethodSource source, Throwable failure, int depth) {
        if (depth > 16) {
            return "";
        }
        String site = stackSite(source, failure);
        if (site.isEmpty()) {
            return "";
        }
        List<String> sites = new ArrayList<>();
        sites.add(site);
        if (failure instanceof MultipleFailuresError multiple) {
            for (Throwable child : multiple.getFailures()) {
                String childSite = failureSite(source, child, depth + 1);
                if (childSite.isEmpty()) {
                    return "";
                }
                sites.add(MutationHash.digest("sentinel-java-child-failure-v1",
                        List.of(child.getClass().getName(), safeMessage(child), childSite)));
            }
        }
        Collections.sort(sites);
        return MutationHash.digest("sentinel-java-failure-sites-v1", sites);
    }

    private static String stackSite(MethodSource source, Throwable failure) {
        String declaringClass = source.getJavaMethod().getDeclaringClass().getName();
        List<String> locations = new ArrayList<>();
        for (StackTraceElement frame : failure.getStackTrace()) {
            locations.add(frame.getClassName());
            locations.add(frame.getMethodName());
            locations.add(Integer.toString(frame.getLineNumber()));
            if (frame.getClassName().equals(declaringClass)
                    && frame.getMethodName().equals(source.getMethodName())) {
                return frame.getLineNumber() > 0
                        ? MutationHash.digest("sentinel-java-failure-site-v1", locations) : "";
            }
        }
        return "";
    }

    static ExecutionStatus classify(Throwable failure) {
        return classify(failure, 0);
    }

    private static ExecutionStatus classify(Throwable failure, int depth) {
        if (!approvedFailure(failure, depth)) {
            return ExecutionStatus.RUNTIME_ERROR;
        }
        if (failure instanceof MultipleFailuresError multiple) {
            if (multiple.getFailures().isEmpty()) {
                return ExecutionStatus.RUNTIME_ERROR;
            }
            for (Throwable nested : multiple.getFailures()) {
                if (classify(nested, depth + 1) != ExecutionStatus.ASSERTION_FAILURE) {
                    return ExecutionStatus.RUNTIME_ERROR;
                }
            }
            return ExecutionStatus.ASSERTION_FAILURE;
        }
        return ExecutionStatus.ASSERTION_FAILURE;
    }

    private static boolean approvedFailure(Throwable failure, int depth) {
        return depth <= 16 && failure != null
                && MutationNormalizer.approvedAssertionType(failure.getClass().getName());
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null ? "" : message;
    }

    private static void increment(Map<String, Integer> counts, String id) {
        Integer count = counts.get(id);
        counts.put(id, count == null ? 1 : count + 1);
    }

    @Override
    public synchronized void testPlanExecutionFinished(TestPlan testPlan) {
        if (request == null) {
            return;
        }
        try {
            completed = summary();
            writeSummary(completed);
        } catch (IOException error) {
            throw new IllegalStateException("junitEventWriteFailed", error);
        }
    }

    public synchronized String resultsSha256() {
        List<String> ordered = new ArrayList<>(results);
        Collections.sort(ordered);
        return MutationHash.digest("sentinel-java-test-results-v1", ordered);
    }

    public synchronized ExecutionStatus executionStatus() {
        if (completed == null) {
            throw new IllegalStateException("junitExecutionIncomplete");
        }
        return completed.status();
    }

    private Summary summary() {
        boolean retry = repeated(starts) || repeated(finishes) || repeated(skipped);
        boolean incomplete = expected.isEmpty()
                || starts.isEmpty()
                || !expected.equals(union(starts.keySet(), skipped.keySet()))
                || !expected.equals(union(finishes.keySet(), skipped.keySet()))
                || !Collections.disjoint(starts.keySet(), skipped.keySet());
        ExecutionStatus status = aggregateStatus(incomplete || retry);
        return new Summary(status, retry, failureTestId(status), assertionType(status), signature(status));
    }

    private ExecutionStatus aggregateStatus(boolean invalidEvents) {
        if (invalidEvents) {
            return ExecutionStatus.TOOL_ERROR;
        }
        if (runtimeFailure) {
            return ExecutionStatus.RUNTIME_ERROR;
        }
        if (!assertionFailures.isEmpty()) {
            return ExecutionStatus.ASSERTION_FAILURE;
        }
        return ExecutionStatus.PASSED;
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> values = new HashSet<>(first);
        values.addAll(second);
        return values;
    }

    private static boolean repeated(Map<String, Integer> counts) {
        for (Integer count : counts.values()) {
            if (count != 1) {
                return true;
            }
        }
        return false;
    }

    private String failureTestId(ExecutionStatus status) {
        if (status != ExecutionStatus.ASSERTION_FAILURE) {
            return "";
        }
        List<String> ids = new ArrayList<>();
        for (Failure failure : assertionFailures) {
            ids.add(failure.testId());
        }
        Collections.sort(ids);
        return MutationHash.digest("sentinel-java-failed-tests-v1", ids);
    }

    private String assertionType(ExecutionStatus status) {
        if (status != ExecutionStatus.ASSERTION_FAILURE) {
            return "";
        }
        String type = assertionFailures.get(0).type();
        for (Failure failure : assertionFailures) {
            if (!type.equals(failure.type())) {
                return "multiple";
            }
        }
        return type;
    }

    private String signature(ExecutionStatus status) {
        if (status != ExecutionStatus.ASSERTION_FAILURE) {
            return "";
        }
        List<String> values = new ArrayList<>();
        for (Failure failure : assertionFailures) {
            values.add(MutationHash.digest("sentinel-java-assertion-v2", List.of(
                    failure.testId(), failure.type(), failure.message(), failure.site())));
        }
        Collections.sort(values);
        return MutationHash.digest("sentinel-java-failure-signature-v2", values);
    }

    private void writeSummary(Summary summary) throws IOException {
        List<String> ids = new ArrayList<>(expected);
        Collections.sort(ids);
        List<String> skippedIds = new ArrayList<>(skipped.keySet());
        Collections.sort(skippedIds);
        for (String id : skippedIds) {
            ids.add("skipped\n" + id);
        }
        String inventory = MutationHash.digest("sentinel-java-test-inventory-v1", ids);
        var execution = new io.github.hwainhwang.sentinel.mutation.TestExecution(
                summary.status(),
                inventory,
                summary.testId(),
                summary.assertionType(),
                summary.failureSignature(),
                request.nonce(),
                request.cacheObserved(),
                summary.retryObserved());
        byte[] event = new JUnitEventSummary(request.sourceSha256(), execution)
                .authenticatedFile(request.hmacKey());
        JUnitPrivateFiles.write(request.eventFile(), event);
    }

    private record Failure(String testId, String type, String message, String site) {}

    private record Summary(
            ExecutionStatus status,
            boolean retryObserved,
            String testId,
            String assertionType,
            String failureSignature) {}
}
