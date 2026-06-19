package com.proxyapp.temporal.workflow;
import com.proxyapp.control.model.AppliedStatus;
import com.proxyapp.control.model.CatalogEntryDto;
import com.proxyapp.control.model.ProxyControlState;

import com.proxyapp.routing.model.EdgeConfig;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.List;

/**
 * Singleton control workflow per install (Workflow ID {@code proxy-control}). The cloud
 * drives it via signals over its egress connection to Temporal; the proxy polls it via
 * query and hot-applies changes. This indirection is what lets an egress-only proxy be
 * remotely controlled.
 */
@WorkflowInterface
public interface ProxyControlWorkflow {

    String WORKFLOW_ID = "proxy-control";

    @WorkflowMethod
    void run(ProxyControlState initialState);

    @SignalMethod
    void enable();

    @SignalMethod
    void disable();

    /** Replace the full device/routing config. Rejected (with {@code lastError}) if invalid. */
    @SignalMethod
    void applyConfig(List<EdgeConfig> devices);

    @SignalMethod
    void upsertDevice(EdgeConfig device);

    @SignalMethod
    void removeDevice(String deviceId);

    /** Add or replace one message type in the catalog. Rejected (with {@code lastError}) if invalid. */
    @SignalMethod
    void upsertMessageType(CatalogEntryDto entry);

    /** Remove a message type. Rejected if any device binding still references it. */
    @SignalMethod
    void removeMessageType(String typeName);

    /** Replace the whole catalog (profile import / reset). Rejected if it would orphan a binding. */
    @SignalMethod
    void importCatalog(List<CatalogEntryDto> entries);

    /** Ask the proxy process to shut down gracefully (supervisor decides what happens next). */
    @SignalMethod
    void requestShutdown();

    /** Ask the proxy process to restart: graceful exit + supervisor relaunch. */
    @SignalMethod
    void requestRestart();

    /** Sent by the proxy just before it acts on a lifecycle command, clearing it. */
    @SignalMethod
    void ackLifecycle(String requestId);

    /** Sent by the proxy after each reconcile so the cloud can see desired vs applied. */
    @SignalMethod
    void reportApplied(AppliedStatus status);

    @QueryMethod
    ProxyControlState getState();
}
