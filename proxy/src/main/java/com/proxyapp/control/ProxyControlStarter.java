package com.proxyapp.control;
import com.proxyapp.control.model.CatalogEntryDto;
import com.proxyapp.control.model.ProxyControlState;
import com.proxyapp.temporal.workflow.ProxyControlWorkflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proxyapp.config.ProxyProperties;
import com.proxyapp.routing.model.EdgeConfig;
import com.proxyapp.routing.MessageCatalog;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Ensures the singleton control workflow exists, seeding it with the catalog's slim view,
 * the site port pool, and (for the demo) any bootstrap device config. Idempotent: with
 * conflict policy USE_EXISTING a running workflow is left untouched, so Temporal — not the
 * local bootstrap file — stays the source of truth for operational config.
 */
public class ProxyControlStarter {

    private static final Logger log = LoggerFactory.getLogger(ProxyControlStarter.class);

    private final WorkflowClient workflowClient;
    private final ProxyProperties properties;
    private final MessageCatalog catalog;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProxyControlStarter(WorkflowClient workflowClient, ProxyProperties properties,
                               MessageCatalog catalog, ResourceLoader resourceLoader) {
        this.workflowClient = workflowClient;
        this.properties = properties;
        this.catalog = catalog;
        this.resourceLoader = resourceLoader;
    }

    public void ensureStarted() {
        ProxyControlState seed = new ProxyControlState();
        seed.setEnabled(true);
        seed.setDevices(loadSeedDevices());
        seed.setTypeDirections(catalog.typeDirections());
        seed.setCatalogEntries(catalog.entries().stream()
                .map(CatalogEntryDto::from)
                .collect(Collectors.toCollection(ArrayList::new)));
        seed.setTcpPortPool(properties.tcpPortPool());

        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(ProxyControlWorkflow.WORKFLOW_ID)
                .setTaskQueue(properties.controlTaskQueue())
                .setWorkflowIdConflictPolicy(
                        WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                .build();
        ProxyControlWorkflow workflow =
                workflowClient.newWorkflowStub(ProxyControlWorkflow.class, options);
        WorkflowClient.start(workflow::run, seed);
        log.info("control workflow '{}' ensured on task queue '{}'",
                ProxyControlWorkflow.WORKFLOW_ID, properties.controlTaskQueue());
    }

    private List<EdgeConfig> loadSeedDevices() {
        String location = properties.seed() == null ? null : properties.seed().devicesResource();
        if (location == null || location.isBlank()) {
            return List.of();
        }
        try {
            Resource resource = resourceLoader.getResource(location);
            try (var in = resource.getInputStream()) {
                List<EdgeConfig> devices = objectMapper.readValue(in, new TypeReference<>() {
                });
                log.info("seeding control workflow with {} device(s) from {}", devices.size(), location);
                return devices;
            }
        } catch (Exception e) {
            log.warn("could not load seed devices from {}: {}", location, e.getMessage());
            return List.of();
        }
    }
}
