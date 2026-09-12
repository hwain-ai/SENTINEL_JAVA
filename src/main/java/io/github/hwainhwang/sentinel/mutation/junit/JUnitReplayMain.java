package io.github.hwainhwang.sentinel.mutation.junit;

import io.github.hwainhwang.sentinel.mutation.ExecutionStatus;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/** Runs the complete explicitly selected Jupiter test set in one fresh child JVM. */
public final class JUnitReplayMain {
    private JUnitReplayMain() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] arguments) throws Exception {
        int exit = run(arguments);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    static int run(String[] arguments) throws Exception {
        JUnitRequest request = JUnitRequest.readIfPresent();
        if (request == null || arguments.length == 0) {
            throw new IllegalArgumentException("junitReplayRequestMissing");
        }
        var listener = new SentinelTestExecutionListener();
        var launcher = LauncherFactory.create(configuration());
        var discovery = LauncherDiscoveryRequestBuilder.request()
                .configurationParameter("junit.jupiter.execution.parallel.enabled", "false")
                .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "false");
        for (String name : arguments) {
            discovery.selectors(DiscoverySelectors.selectClass(name));
        }
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(discovery.build());
        JUnitReplaySummary.write(request, listener.resultsSha256());
        // RISK(side-effect): 1 is a completed test failure; all other nonzero exits are tool failures.
        return listener.executionStatus() == ExecutionStatus.PASSED ? 0 : 1;
    }

    private static LauncherConfig configuration() throws ReflectiveOperationException {
        TestEngine jupiter = (TestEngine) Class.forName("org.junit.jupiter.engine.JupiterTestEngine")
                .getConstructor().newInstance();
        return LauncherConfig.builder().enableTestEngineAutoRegistration(false)
                .enableTestExecutionListenerAutoRegistration(false)
                .enableLauncherSessionListenerAutoRegistration(false)
                .enableLauncherDiscoveryListenerAutoRegistration(false)
                .enablePostDiscoveryFilterAutoRegistration(false)
                .addTestEngines(jupiter).build();
    }
}
