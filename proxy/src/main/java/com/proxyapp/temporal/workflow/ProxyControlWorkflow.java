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
 * Singleton control workflow per install (Workflow ID {@code proxy-control}) — the durable source
 * of truth for operational config. It is <b>push-based</b>: config changes arrive as Updates that
 * validate, mutate, and return the resulting state synchronously; on each accepted change the
 * workflow schedules a reconcile <i>activity</i> on the proxy worker (it does not poll). Between
 * changes the workflow parks on a no-timeout {@code Workflow.await} and costs no Actions.
 *
 * <p>Everything rides the egress gRPC connection; the proxy never opens an inbound port. This
 * indirection is what lets an egress-only proxy be remotely controlled.
 */
@WorkflowInterface
public interface ProxyControlWorkflow {

    String WORKFLOW_ID = "proxy-control";

    @WorkflowMethod
    void run(ProxyControlState initialState);

    /**
     * Config changes are <b>Updates</b>: each validates, mutates, and returns the resulting control
     * state synchronously — {@code version} is bumped and {@code lastError} cleared on accept;
     * {@code lastError} is set and {@code version} left unchanged on reject. The cloud reads the
     * returned state and persists it to its H2 read model, so no confirmation Query is needed.
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

    /**
     * Ask the workflow to (re)apply current desired state now: manual repair, the proxy's one-shot
     * boot sync, and drift self-heal. Fire-and-forget — it just wakes the reconcile loop.
     */
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
     * The proxy reports its applied state (link-health transitions) as an <b>Update</b>; the return
     * value is the current desired {@code version} so the proxy can detect drift and self-heal via
     * {@link #requestReconcile()}. Routine post-reconcile applied state is captured for free as the
     * reconcile activity's return value, so this fires only on transitions between reconciles.
     */
    @UpdateMethod
    long reportApplied(AppliedStatus status);

    @QueryMethod
    ProxyControlState getState();
}
