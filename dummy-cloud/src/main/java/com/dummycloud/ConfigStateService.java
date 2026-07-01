package com.dummycloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Control-plane mediator + UI read model. Writes go to the Temporal control workflow (the source of
 * truth) first; only an ACCEPTED change is persisted to H2 (the UI's read model). Reads are served
 * from H2 so the UI never pays a Temporal Query — a billable Action — to hydrate. Data flows one way:
 * workflow -> H2. H2 is never pushed back into the workflow.
 */
@Service
public class ConfigStateService {

    private static final Logger log = LoggerFactory.getLogger(ConfigStateService.class);

    private final WorkflowClient workflowClient;
    private final CloudProperties properties;
    private final StoredConfigRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();

    public ConfigStateService(WorkflowClient workflowClient, CloudProperties properties,
                              StoredConfigRepository repository) {
        this.workflowClient = workflowClient;
        this.properties = properties;
        this.repository = repository;
    }

    /** The persisted desired-config JSON the UI hydrates from (empty default if nothing configured). */
    public JsonNode readState() {
        return repository.findById(namespace())
                .map(c -> parseOrEmpty(c.getStateJson()))
                .orElseGet(this::emptyState);
    }

    /**
     * Send the change as a Workflow <b>Update</b>: the workflow validates, mutates, and returns the
     * resulting state synchronously (one Action, no confirmation Query poll). The returned
     * {@code lastError} is the outcome — null means accepted (persist to H2), non-null means
     * rejected. Returns {@code {accepted, version}} or {@code {accepted:false, message}} for the UI.
     */
    public Map<String, Object> applyChange(String updateName, Object arg) {
        try {
            WorkflowStub stub = controlStub();
            JsonNode after = (arg != null)
                    ? stub.update(updateName, JsonNode.class, arg)
                    : stub.update(updateName, JsonNode.class);
            long version = after.path("version").asLong(-1);
            String error = textOrNull(after, "lastError");
            if (error == null) {
                persist(after, version);
                return result(true, version, null);
            }
            return result(false, version, error);
        } catch (Exception e) {
            log.warn("control update '{}' failed: {}", updateName, e.getMessage());
            return result(false, -1, "control workflow unavailable: " + e.getMessage());
        }
    }

    /** One-time: seed H2 from the workflow's current state if we have no row yet for this namespace. */
    public void hydrateIfEmpty() {
        if (repository.existsById(namespace())) {
            return;
        }
        try {
            JsonNode state = queryState();
            persist(state, state.path("version").asLong(0));
            log.info("hydrated H2 read model for namespace '{}' from the control workflow", namespace());
        } catch (RuntimeException e) {
            log.info("no control-workflow state to hydrate yet for '{}' ({}); UI starts empty",
                    namespace(), e.getMessage());
        }
    }

    private void persist(JsonNode state, long version) {
        repository.save(new StoredConfig(namespace(), state.toString(), version, Instant.now().toString()));
    }

    private JsonNode queryState() {
        return controlStub().query("getState", JsonNode.class);
    }

    private WorkflowStub controlStub() {
        return workflowClient.newUntypedWorkflowStub(properties.proxy().controlWorkflowId());
    }

    private String namespace() {
        return properties.temporal().namespace();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Map<String, Object> result(boolean accepted, long version, String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("accepted", accepted);
        r.put("version", version);
        if (message != null) {
            r.put("message", message);
        }
        return r;
    }

    private JsonNode parseOrEmpty(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return emptyState();
        }
    }

    /** A well-formed empty state so the UI renders a clean slate before anything is configured. */
    private JsonNode emptyState() {
        ObjectNode s = mapper.createObjectNode();
        s.put("enabled", true);
        s.put("version", 0);
        s.putNull("lastError");
        s.set("devices", mapper.createArrayNode());
        s.set("catalogEntries", mapper.createArrayNode());
        s.set("typeDirections", mapper.createObjectNode());
        return s;
    }
}
