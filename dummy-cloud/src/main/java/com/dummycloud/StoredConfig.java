package com.dummycloud;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * The cloud app's persisted read model of an install's desired config — one row per Temporal
 * namespace. Strictly for hydrating the UI cheaply and across restarts; the Temporal control
 * workflow remains the source of truth for the proxy. Written only after the workflow ACCEPTS a
 * change (see {@link ConfigStateService}) and never pushed back into the workflow.
 */
@Entity
@Table(name = "stored_config")
public class StoredConfig {

    @Id
    private String namespace;

    /** The accepted {@code getState} JSON (desired config the UI renders). */
    @Lob
    private String stateJson;

    private long version;

    private String updatedAt;

    protected StoredConfig() {
        // for JPA
    }

    public StoredConfig(String namespace, String stateJson, long version, String updatedAt) {
        this.namespace = namespace;
        this.stateJson = stateJson;
        this.version = version;
        this.updatedAt = updatedAt;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getStateJson() {
        return stateJson;
    }

    public long getVersion() {
        return version;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }
}
