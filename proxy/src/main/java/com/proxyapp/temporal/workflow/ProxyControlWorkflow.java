package com.proxyapp.temporal.workflow;

import com.proxyapp.control.model.AppliedStatus;
import com.proxyapp.control.model.CatalogEntryDto;
import com.proxyapp.control.model.ProxyControlState;
import com.proxyapp.routing.model.EdgeConfig;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.List;

/**
 * Singleton control workflow per install — the durable source of truth for operational config.
 * Push-based: each accepted change schedules a reconcile activity on the proxy worker rather than
 * polling, and between changes the workflow parks on a no-timeout await, costing no Actions.
 * Everything rides the egress gRPC connection; the proxy never opens an inbound port.
 */
@WorkflowInterface
public interface ProxyControlWorkflow {

    String WORKFLOW_ID = "proxy-control";

    @WorkflowMethod
    void run(ProxyControlState initialState);

    /**
     * Config changes are Updates: each validates, mutates, and returns the resulting state
     * synchronously. Accept bumps {@code version} and clears {@code lastError}; reject sets
     * {@code lastError} and leaves {@code version} alone. No confirmation Query is needed.
     */
    @UpdateMethod
    ProxyControlState enable();

    @UpdateMethod
    ProxyControlState disable();

    /** Replace the full device/routing config. Rejected (with {@code lastError}) if invalid. */
    @UpdateMethod
    ProxyControlState applyConfig(List<EdgeConfig> devices);

    @UpdateMethod
    ProxyControlState upsertDevice(EdgeConfig device);

    @UpdateMethod
    ProxyControlState removeDevice(String deviceId);

    /** Add or replace one message type in the catalog. Rejected (with {@code lastError}) if invalid. */
    @UpdateMethod
    ProxyControlState upsertMessageType(CatalogEntryDto entry);

    /** Remove a message type. Rejected if any device binding still references it. */
    @UpdateMethod
    ProxyControlState removeMessageType(String typeName);

    /** Replace the whole catalog (profile import / reset). Rejected if it would orphan a binding. */
    @UpdateMethod
    ProxyControlState importCatalog(List<CatalogEntryDto> entries);

    /** Re-apply current desired state now: manual repair, the proxy's boot sync, drift self-heal. */
    @SignalMethod
    void requestReconcile();

    /** Ask the proxy process to shut down gracefully (supervisor decides what happens next). */
    @SignalMethod
    void requestShutdown();

    /** Ask the proxy process to restart: graceful exit + supervisor relaunch. */
    @SignalMethod
    void requestRestart();

    /** Sent by the proxy just before it acts on a lifecycle command, clearing it durably. */
    @SignalMethod
    void ackLifecycle(String requestId);

    /**
     * The proxy reports applied state (link-health transitions); returns the current desired
     * {@code version} so the proxy can detect drift and self-heal via {@link #requestReconcile()}.
     * Fires only on transitions between reconciles — routine applied state comes back free as the
     * reconcile activity's return value.
     */
    @UpdateMethod
    long reportApplied(AppliedStatus status);

    /**
     * On-demand live link check, where {@link #getState()} returns the last-reported snapshot. Reads
     * session state straight from the sockets via a {@code ProbeSessions} activity, so an unreachable
     * proxy fails fast instead of returning a stale UP. No version bump, no reconcile wake.
     */
    @UpdateMethod
    AppliedStatus checkSessions();

    @QueryMethod
    ProxyControlState getState();
}
