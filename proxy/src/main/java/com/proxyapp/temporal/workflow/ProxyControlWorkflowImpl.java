package com.proxyapp.temporal.workflow;
import com.proxyapp.control.CatalogValidator;
import com.proxyapp.control.model.AppliedStatus;
import com.proxyapp.control.model.CatalogEntryDto;
import com.proxyapp.control.model.ProxyControlState;

import com.proxyapp.routing.ConfigValidator;
import com.proxyapp.routing.model.EdgeConfig;
import com.proxyapp.routing.model.RouteBinding;
import com.proxyapp.temporal.activity.ControlActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInit;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Push-based control plane. The workflow holds desired state; config Updates mutate it and bump
 * {@code version}; the run loop wakes on a version change (or a manual/boot {@code requestReconcile},
 * or a lifecycle command) and pushes the work to the proxy as an <b>activity</b> — reconcile applies
 * config, deliverLifecycle hands a restart/shutdown to the proxy (which exits out-of-band). Between
 * events the loop parks on a no-timeout {@link Workflow#await} (zero Actions), and continues-as-new
 * only when the server suggests it via {@link io.temporal.workflow.WorkflowInfo#isContinueAsNewSuggested()}.
 */
@WorkflowImpl(taskQueues = "${proxy.control-task-queue}")
public class ProxyControlWorkflowImpl implements ProxyControlWorkflow {

    private static final Logger log = Workflow.getLogger(ProxyControlWorkflowImpl.class);

    /**
     * Reconcile must converge, so it retries forever (it is idempotent; a validation mismatch only
     * logs and returns, it does not throw). Lifecycle delivery is attempt-once: the proxy acks
     * durably before it exits, so an automatic retry could otherwise re-trigger an exit loop.
     */
    private final ControlActivities activities = Workflow.newActivityStub(
            ControlActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(0).build())
                    .build(),
            Map.of("DeliverLifecycle", ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(15))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                    .build()));

    private final ProxyControlState state;

    /** Last desired version the proxy has been asked to apply; -1 forces a reconcile on (re)start. */
    private long reconciledVersion = -1;
    /** A manual/boot/self-heal reconcile request, independent of a version change. */
    private boolean reconcileRequested;
    /** Lifecycle requestId we have already attempted to deliver — deliver each command once. */
    private String deliveredLifecycle = "";

    @WorkflowInit
    public ProxyControlWorkflowImpl(ProxyControlState initialState) {
        // Initialized in the constructor so signals/updates delivered before run() see valid state.
        this.state = initialState != null ? initialState : new ProxyControlState();
    }

    @Override
    public void run(ProxyControlState initialState) {
        while (true) {
            Workflow.await(() -> state.getVersion() != reconciledVersion
                    || lifecyclePending()
                    || reconcileRequested);

            if (lifecyclePending()) {
                // Deliver once; the proxy acks (clearing the command) and exits OUTSIDE the activity.
                // Tracked by requestId so a proxy that can't be reached doesn't spin the loop.
                deliveredLifecycle = state.getLifecycleRequestId();
                try {
                    activities.deliverLifecycle(state.getLifecycleCommand(), state.getLifecycleRequestId());
                } catch (ActivityFailure e) {
                    log.warn("lifecycle '{}' delivery failed (proxy unreachable?): {}",
                            state.getLifecycleCommand(), e.getMessage());
                }
            }

            if (state.getVersion() != reconciledVersion || reconcileRequested) {
                reconcileRequested = false;
                long target = state.getVersion();
                // Pass desired state as the activity input (durable snapshot); the proxy applies it
                // and returns what it actually has running, which we record for the cloud to read.
                state.setApplied(activities.reconcile(state));
                reconciledVersion = target;
            }

            if (Workflow.getInfo().isContinueAsNewSuggested()) {
                Workflow.continueAsNew(state);
            }
        }
    }

    /** A restart/shutdown that hasn't yet been handed to the proxy this run. */
    private boolean lifecyclePending() {
        return !ProxyControlState.LIFECYCLE_NONE.equals(state.getLifecycleCommand())
                && state.getLifecycleRequestId() != null
                && !state.getLifecycleRequestId().equals(deliveredLifecycle);
    }

    @Override
    public ProxyControlState enable() {
        state.setEnabled(true);
        return accept("enable");
    }

    @Override
    public ProxyControlState disable() {
        state.setEnabled(false);
        return accept("disable");
    }

    @Override
    public ProxyControlState applyConfig(List<EdgeConfig> devices) {
        List<String> errors = ConfigValidator.validate(
                state.getTypeDirections(), state.getTcpPortPool(), devices);
        if (!errors.isEmpty()) {
            return reject("applyConfig", errors);
        }
        state.setDevices(new ArrayList<>(devices));
        return accept("applyConfig");
    }

    @Override
    public ProxyControlState upsertDevice(EdgeConfig device) {
        List<EdgeConfig> proposed = new ArrayList<>(state.getDevices());
        proposed.removeIf(d -> d.deviceId() != null && d.deviceId().equals(device.deviceId()));
        proposed.add(device);
        List<String> errors = ConfigValidator.validate(
                state.getTypeDirections(), state.getTcpPortPool(), proposed);
        if (!errors.isEmpty()) {
            return reject("upsertDevice", errors);
        }
        state.setDevices(proposed);
        return accept("upsertDevice");
    }

    @Override
    public ProxyControlState removeDevice(String deviceId) {
        List<EdgeConfig> proposed = new ArrayList<>(state.getDevices());
        boolean removed = proposed.removeIf(d -> deviceId.equals(d.deviceId()));
        if (!removed) {
            return reject("removeDevice", List.of("no device with id " + deviceId));
        }
        state.setDevices(proposed);
        return accept("removeDevice");
    }

    @Override
    public ProxyControlState upsertMessageType(CatalogEntryDto entry) {
        List<String> errors = CatalogValidator.validateEntry(entry, CatalogValidator.KNOWN_CODECS);
        if (!errors.isEmpty()) {
            return reject("upsertMessageType", errors);
        }
        List<CatalogEntryDto> proposed = new ArrayList<>(currentCatalog());
        proposed.removeIf(e -> e.type().equals(entry.type()));
        proposed.add(entry);
        setCatalog(proposed);
        return accept("upsertMessageType");
    }

    @Override
    public ProxyControlState removeMessageType(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return reject("removeMessageType", List.of("message type name must not be blank"));
        }
        List<CatalogEntryDto> proposed = new ArrayList<>(currentCatalog());
        boolean present = proposed.stream().anyMatch(e -> typeName.equals(e.type()));
        if (!present) {
            return reject("removeMessageType", List.of("no message type named " + typeName));
        }
        List<String> users = devicesReferencing(typeName);
        if (!users.isEmpty()) {
            return reject("removeMessageType", List.of("message type " + typeName
                    + " is referenced by device(s): " + String.join(", ", users)));
        }
        proposed.removeIf(e -> typeName.equals(e.type()));
        setCatalog(proposed);
        return accept("removeMessageType");
    }

    @Override
    public ProxyControlState importCatalog(List<CatalogEntryDto> entries) {
        List<String> errors = CatalogValidator.validateCatalog(entries, CatalogValidator.KNOWN_CODECS);
        if (!errors.isEmpty()) {
            return reject("importCatalog", errors);
        }
        Set<String> newTypes = entries.stream().map(CatalogEntryDto::type)
                .collect(Collectors.toSet());
        List<String> orphaned = new ArrayList<>();
        for (EdgeConfig device : state.getDevices()) {
            for (RouteBinding binding : device.bindings()) {
                if (binding.messageType() != null
                        && !newTypes.contains(binding.messageType().value())) {
                    orphaned.add(device.deviceId() + "/" + binding.messageType().value());
                }
            }
        }
        if (!orphaned.isEmpty()) {
            return reject("importCatalog", List.of("catalog import would orphan device binding(s): "
                    + String.join(", ", orphaned)));
        }
        setCatalog(new ArrayList<>(entries));
        return accept("importCatalog");
    }

    @Override
    public void requestReconcile() {
        reconcileRequested = true;
        log.info("reconcile requested (manual/boot/self-heal)");
    }

    @Override
    public void requestShutdown() {
        requestLifecycle(ProxyControlState.LIFECYCLE_SHUTDOWN);
    }

    @Override
    public void requestRestart() {
        requestLifecycle(ProxyControlState.LIFECYCLE_RESTART);
    }

    @Override
    public void ackLifecycle(String requestId) {
        if (requestId == null || !requestId.equals(state.getLifecycleRequestId())) {
            return; // stale or replayed ack for a command that was already superseded
        }
        log.info("lifecycle command '{}' acknowledged by proxy", state.getLifecycleCommand());
        state.setLifecycleCommand(ProxyControlState.LIFECYCLE_NONE);
        state.setLifecycleRequestId(null);
    }

    @Override
    public long reportApplied(AppliedStatus status) {
        // Applied state is observability only — record it without bumping version or waking the
        // reconcile loop. Return desired version so the proxy can detect drift and self-heal.
        state.setApplied(status);
        return state.getVersion();
    }

    @Override
    public ProxyControlState getState() {
        return state;
    }

    private void requestLifecycle(String command) {
        state.setLifecycleCommand(command);
        state.setLifecycleRequestId(Workflow.randomUUID().toString());
        log.info("lifecycle command '{}' requested ({})", command, state.getLifecycleRequestId());
    }

    /**
     * The catalog to mutate. On a workflow that predates Part 3 the stored catalog is null;
     * synthesize a degraded one from {@code typeDirections} (codec defaults to json, endpoints
     * blank) so a single edit doesn't NPE. The UI steers operators to "Import profile" instead,
     * which carries the full entries.
     */
    private List<CatalogEntryDto> currentCatalog() {
        if (state.getCatalogEntries() != null) {
            return state.getCatalogEntries();
        }
        List<CatalogEntryDto> synthesized = new ArrayList<>();
        state.getTypeDirections().forEach((type, direction) ->
                synthesized.add(new CatalogEntryDto(type, direction, "json", null, null)));
        return synthesized;
    }

    /** Store the catalog and recompute the derived typeDirections projection in one place. */
    private void setCatalog(List<CatalogEntryDto> entries) {
        state.setCatalogEntries(entries);
        Map<String, String> typeDirections = new LinkedHashMap<>();
        for (CatalogEntryDto entry : entries) {
            typeDirections.put(entry.type(), entry.direction());
        }
        state.setTypeDirections(typeDirections);
    }

    private List<String> devicesReferencing(String typeName) {
        List<String> users = new ArrayList<>();
        for (EdgeConfig device : state.getDevices()) {
            boolean references = device.bindings().stream().anyMatch(binding ->
                    binding.messageType() != null && typeName.equals(binding.messageType().value()));
            if (references) {
                users.add(device.deviceId());
            }
        }
        return users;
    }

    private ProxyControlState accept(String change) {
        state.setVersion(state.getVersion() + 1);
        state.setLastError(null);
        log.info("control change '{}' accepted, version now {}", change, state.getVersion());
        return state;
    }

    private ProxyControlState reject(String change, List<String> errors) {
        // Rejected changes never go live; the reason rides the returned state's lastError.
        state.setLastError(change + " rejected: " + String.join("; ", errors));
        log.warn("control change '{}' rejected: {}", change, errors);
        return state;
    }
}
