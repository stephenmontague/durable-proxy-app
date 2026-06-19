package com.proxyapp.temporal.workflow;
import com.proxyapp.control.CatalogValidator;
import com.proxyapp.control.model.AppliedStatus;
import com.proxyapp.control.model.CatalogEntryDto;
import com.proxyapp.control.model.ProxyControlState;

import com.proxyapp.routing.ConfigValidator;
import com.proxyapp.routing.model.EdgeConfig;
import com.proxyapp.routing.model.RouteBinding;
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

@WorkflowImpl(taskQueues = "${proxy.control-task-queue}")
public class ProxyControlWorkflowImpl implements ProxyControlWorkflow {

    /** Continue-as-new after this many accepted/rejected changes, or daily, to bound history. */
    private static final int MAX_CHANGES_PER_RUN = 500;
    private static final Duration MAX_RUN_DURATION = Duration.ofHours(24);

    private static final Logger log = Workflow.getLogger(ProxyControlWorkflowImpl.class);

    private final ProxyControlState state;
    private int changes;

    @WorkflowInit
    public ProxyControlWorkflowImpl(ProxyControlState initialState) {
        // Initialized in the constructor so signals delivered before run() see valid state.
        this.state = initialState != null ? initialState : new ProxyControlState();
    }

    @Override
    public void run(ProxyControlState initialState) {
        Workflow.await(MAX_RUN_DURATION, () -> changes >= MAX_CHANGES_PER_RUN);
        Workflow.continueAsNew(state);
    }

    @Override
    public void enable() {
        state.setEnabled(true);
        accept("enable");
    }

    @Override
    public void disable() {
        state.setEnabled(false);
        accept("disable");
    }

    @Override
    public void applyConfig(List<EdgeConfig> devices) {
        List<String> errors = ConfigValidator.validate(
                state.getTypeDirections(), state.getTcpPortPool(), devices);
        if (!errors.isEmpty()) {
            reject("applyConfig", errors);
            return;
        }
        state.setDevices(new ArrayList<>(devices));
        accept("applyConfig");
    }

    @Override
    public void upsertDevice(EdgeConfig device) {
        List<EdgeConfig> proposed = new ArrayList<>(state.getDevices());
        proposed.removeIf(d -> d.deviceId() != null && d.deviceId().equals(device.deviceId()));
        proposed.add(device);
        List<String> errors = ConfigValidator.validate(
                state.getTypeDirections(), state.getTcpPortPool(), proposed);
        if (!errors.isEmpty()) {
            reject("upsertDevice", errors);
            return;
        }
        state.setDevices(proposed);
        accept("upsertDevice");
    }

    @Override
    public void removeDevice(String deviceId) {
        List<EdgeConfig> proposed = new ArrayList<>(state.getDevices());
        boolean removed = proposed.removeIf(d -> deviceId.equals(d.deviceId()));
        if (!removed) {
            reject("removeDevice", List.of("no device with id " + deviceId));
            return;
        }
        state.setDevices(proposed);
        accept("removeDevice");
    }

    @Override
    public void upsertMessageType(CatalogEntryDto entry) {
        List<String> errors = CatalogValidator.validateEntry(entry, CatalogValidator.KNOWN_CODECS);
        if (!errors.isEmpty()) {
            reject("upsertMessageType", errors);
            return;
        }
        List<CatalogEntryDto> proposed = new ArrayList<>(currentCatalog());
        proposed.removeIf(e -> e.type().equals(entry.type()));
        proposed.add(entry);
        setCatalog(proposed);
        accept("upsertMessageType");
    }

    @Override
    public void removeMessageType(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            reject("removeMessageType", List.of("message type name must not be blank"));
            return;
        }
        List<CatalogEntryDto> proposed = new ArrayList<>(currentCatalog());
        boolean present = proposed.stream().anyMatch(e -> typeName.equals(e.type()));
        if (!present) {
            reject("removeMessageType", List.of("no message type named " + typeName));
            return;
        }
        List<String> users = devicesReferencing(typeName);
        if (!users.isEmpty()) {
            reject("removeMessageType", List.of("message type " + typeName
                    + " is referenced by device(s): " + String.join(", ", users)));
            return;
        }
        proposed.removeIf(e -> typeName.equals(e.type()));
        setCatalog(proposed);
        accept("removeMessageType");
    }

    @Override
    public void importCatalog(List<CatalogEntryDto> entries) {
        List<String> errors = CatalogValidator.validateCatalog(entries, CatalogValidator.KNOWN_CODECS);
        if (!errors.isEmpty()) {
            reject("importCatalog", errors);
            return;
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
            reject("importCatalog", List.of("catalog import would orphan device binding(s): "
                    + String.join(", ", orphaned)));
            return;
        }
        setCatalog(new ArrayList<>(entries));
        accept("importCatalog");
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
        changes++;
    }

    @Override
    public void reportApplied(AppliedStatus status) {
        state.setApplied(status);
        changes++;
    }

    @Override
    public ProxyControlState getState() {
        return state;
    }

    private void requestLifecycle(String command) {
        state.setLifecycleCommand(command);
        state.setLifecycleRequestId(Workflow.randomUUID().toString());
        changes++;
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

    private void accept(String change) {
        state.setVersion(state.getVersion() + 1);
        state.setLastError(null);
        changes++;
        log.info("control change '{}' accepted, version now {}", change, state.getVersion());
    }

    private void reject(String change, List<String> errors) {
        // Rejected changes never go live; the reason is surfaced on the queryable state.
        state.setLastError(change + " rejected: " + String.join("; ", errors));
        changes++;
        log.warn("control change '{}' rejected: {}", change, errors);
    }
}
