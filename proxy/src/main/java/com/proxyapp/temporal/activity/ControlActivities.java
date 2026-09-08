package com.proxyapp.temporal.activity;

import com.proxyapp.control.model.AppliedStatus;
import com.proxyapp.control.model.ProxyControlState;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Control-plane activities the {@code proxy-control} workflow schedules on the proxy worker. They
 * run in the proxy JVM, so they can touch the in-process {@code Reconciler} and lifecycle directly.
 */
@ActivityInterface
public interface ControlActivities {

    /**
     * Apply the desired control state in-process and return what the proxy now has running, so the
     * workflow records applied state at no extra Action. Idempotent and retried until it converges;
     * a validation mismatch only logs, keeping the last-good config.
     */
    @ActivityMethod(name = "Reconcile")
    AppliedStatus reconcile(ProxyControlState desired);

    /**
     * Hand a restart/shutdown command to the proxy, which acks durably then exits outside this
     * activity — attempt-once, since a retry could re-trigger an exit loop.
     */
    @ActivityMethod(name = "DeliverLifecycle")
    void deliverLifecycle(String command, String requestId);

    /**
     * Snapshot the proxy's live applied state, reading each device's persistent-TCP link state
     * straight from the session objects in this JVM. Applies nothing. Backs {@code checkSessions},
     * so the cloud gets ground truth rather than the last-reported read model.
     */
    @ActivityMethod(name = "ProbeSessions")
    AppliedStatus probeSessions();
}
