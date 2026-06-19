package com.proxyapp.control.model;
import com.proxyapp.temporal.workflow.ProxyControlWorkflow;

import com.proxyapp.routing.model.EdgeConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Desired state of one proxy install, held durably by the {@link ProxyControlWorkflow}.
 * {@code typeDirections} and {@code tcpPortPool} are seeded by the proxy at first start so
 * signal handlers can validate proposed configs deterministically inside the workflow.
 */
public class ProxyControlState {

    /** Pending process-lifecycle command for the proxy: NONE, SHUTDOWN, or RESTART. */
    public static final String LIFECYCLE_NONE = "NONE";
    public static final String LIFECYCLE_SHUTDOWN = "SHUTDOWN";
    public static final String LIFECYCLE_RESTART = "RESTART";

    private boolean enabled = true;
    private List<EdgeConfig> devices = new ArrayList<>();
    private long version;
    private String lastError;
    private Map<String, String> typeDirections = new LinkedHashMap<>();
    /**
     * The operator-editable message catalog (Part 3). Null on workflows that predate Part 3 —
     * the proxy then falls back to its boot profile catalog. {@code typeDirections} is kept as a
     * derived projection of this so the device-binding validation stays unchanged.
     */
    private List<CatalogEntryDto> catalogEntries;
    private List<Integer> tcpPortPool = new ArrayList<>();
    private String lifecycleCommand = LIFECYCLE_NONE;
    private String lifecycleRequestId;
    private AppliedStatus applied;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<EdgeConfig> getDevices() {
        return devices;
    }

    public void setDevices(List<EdgeConfig> devices) {
        this.devices = devices;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Map<String, String> getTypeDirections() {
        return typeDirections;
    }

    public void setTypeDirections(Map<String, String> typeDirections) {
        this.typeDirections = typeDirections;
    }

    public List<CatalogEntryDto> getCatalogEntries() {
        return catalogEntries;
    }

    public void setCatalogEntries(List<CatalogEntryDto> catalogEntries) {
        this.catalogEntries = catalogEntries;
    }

    public List<Integer> getTcpPortPool() {
        return tcpPortPool;
    }

    public void setTcpPortPool(List<Integer> tcpPortPool) {
        this.tcpPortPool = tcpPortPool;
    }

    public String getLifecycleCommand() {
        return lifecycleCommand;
    }

    public void setLifecycleCommand(String lifecycleCommand) {
        this.lifecycleCommand = lifecycleCommand;
    }

    public String getLifecycleRequestId() {
        return lifecycleRequestId;
    }

    public void setLifecycleRequestId(String lifecycleRequestId) {
        this.lifecycleRequestId = lifecycleRequestId;
    }

    public AppliedStatus getApplied() {
        return applied;
    }

    public void setApplied(AppliedStatus applied) {
        this.applied = applied;
    }
}
