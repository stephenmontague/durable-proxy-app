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
     * workflow can record applied state for free (no extra Action). Idempotent and retried until it
     * converges; a validation mismatch only logs (it does not throw), keeping the last-good config.
     */
    @ActivityMethod(name = "Reconcile")
    AppliedStatus reconcile(ProxyControlState desired);

    /**
     * Hand a restart/shutdown command to the proxy. The proxy acks it durably, then exits OUTSIDE
     * this activity — so this is attempt-once (an automatic retry could re-trigger an exit loop).
     */
    @ActivityMethod(name = "DeliverLifecycle")
    void deliverLifecycle(String command, String requestId);

    /**
     * Read the proxy's LIVE applied state on demand — including each device's current persistent-TCP
     * link state (UP/DOWN/CONNECTING) straight from the session objects in this JVM. Unlike
     * {@link #reconcile}, it applies nothing and has no side effects; it just snapshots. Backs the
     * {@code checkSessions} Update so the cloud can ask "is device X connected right now?" and get
     * ground truth (not the last-reported read model) for one Action per call.
     */
    @ActivityMethod(name = "ProbeSessions")
    AppliedStatus probeSessions();
}
