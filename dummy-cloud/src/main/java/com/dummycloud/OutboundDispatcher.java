package com.dummycloud;

import com.fasterxml.jackson.databind.JsonNode;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Starts the proxy's {@code DeliverToEdge} workflow for any outbound message type.
 * Workflow ID = {type}-{businessId} with REJECT_DUPLICATE + USE_EXISTING gives
 * exactly-once dispatch semantics; because the workflow lands in Temporal first,
 * an offline proxy just means the delivery waits until its worker reconnects.
 */
@Service
public class OutboundDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboundDispatcher.class);
    private static final String DELIVER_TO_EDGE = "DeliverToEdge";

    private final WorkflowClient workflowClient;
    private final CloudProperties properties;

    public OutboundDispatcher(WorkflowClient workflowClient, CloudProperties properties) {
        this.workflowClient = workflowClient;
        this.properties = properties;
    }

    public Map<String, Object> dispatch(String messageType, JsonNode body) {
        String idField = DeviceFleetCatalog.OUTBOUND_BUSINESS_ID_FIELDS.get(messageType);
        JsonNode idNode = idField == null ? null : body.get(idField);
        if (idNode == null || idNode.asText().isBlank()) {
            throw new IllegalArgumentException("payload must carry '" + idField + "'");
        }
        CanonicalMessage message =
                new CanonicalMessage(messageType, idNode.asText(), body.toString());
        String workflowId = message.activityId();

        // Reuse REJECT_DUPLICATE blocks re-dispatch after completion; the default conflict
        // policy (FAIL) makes a concurrent duplicate throw too — both surface as
        // WorkflowExecutionAlreadyStarted so we can report duplicate=true. Either way
        // exactly one execution ever runs.
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(properties.proxy().taskQueue())
                .setWorkflowIdReusePolicy(
                        WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .setMemo(Map.of("origin",
                        "[CLOUD] Dispatched from cloud application (dummy-cloud)"))
                .build();
        try {
            WorkflowStub stub = workflowClient.newUntypedWorkflowStub(DELIVER_TO_EDGE, options);
            stub.start(message);
            log.info("[CLOUD] Started DeliverToEdge workflow {} — task queued in Temporal, " +
                    "waiting for proxy worker to pick it up", workflowId);
            return Map.of("workflowId", workflowId, "duplicate", false);
        } catch (WorkflowExecutionAlreadyStarted e) {
            log.info("duplicate dispatch of {} collapsed", workflowId);
            return Map.of("workflowId", workflowId, "duplicate", true);
        }
    }
}
