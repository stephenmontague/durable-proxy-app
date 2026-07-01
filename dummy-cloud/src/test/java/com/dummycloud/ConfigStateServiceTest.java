package com.dummycloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The control front door now sends each change as a single Workflow Update (no signal + confirmation
 * Query poll). The returned state's {@code lastError} is the outcome: null = accepted (persist to
 * H2), non-null = rejected (no persist). These tests pin that contract with the workflow mocked.
 */
class ConfigStateServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static CloudProperties props() {
        return new CloudProperties(
                new CloudProperties.Temporal("127.0.0.1:7233", "default"),
                new CloudProperties.Proxy("proxy-main", "proxy-control", "proxy-control"));
    }

    @Test
    void acceptedUpdatePersistsAndReportsAccepted() throws Exception {
        WorkflowClient client = mock(WorkflowClient.class);
        WorkflowStub stub = mock(WorkflowStub.class);
        StoredConfigRepository repo = mock(StoredConfigRepository.class);
        when(client.newUntypedWorkflowStub("proxy-control")).thenReturn(stub);

        JsonNode accepted = mapper.readTree(
                "{\"version\":5,\"lastError\":null,\"enabled\":true,\"devices\":[]}");
        when(stub.update("enable", JsonNode.class)).thenReturn(accepted);

        ConfigStateService svc = new ConfigStateService(client, props(), repo);
        Map<String, Object> result = svc.applyChange("enable", null);

        assertThat(result).containsEntry("accepted", true).containsEntry("version", 5L);
        verify(repo).save(any(StoredConfig.class)); // accepted state persisted to the read model
    }

    @Test
    void rejectedUpdateReturnsTheErrorAndDoesNotPersist() throws Exception {
        WorkflowClient client = mock(WorkflowClient.class);
        WorkflowStub stub = mock(WorkflowStub.class);
        StoredConfigRepository repo = mock(StoredConfigRepository.class);
        when(client.newUntypedWorkflowStub("proxy-control")).thenReturn(stub);

        JsonNode rejected = mapper.readTree(
                "{\"version\":4,\"lastError\":\"applyConfig rejected: port 7777 not in pool\"}");
        when(stub.update("disable", JsonNode.class)).thenReturn(rejected);

        ConfigStateService svc = new ConfigStateService(client, props(), repo);
        Map<String, Object> result = svc.applyChange("disable", null);

        assertThat(result).containsEntry("accepted", false);
        assertThat(result.get("message").toString()).contains("rejected");
        verify(repo, never()).save(any());
    }
}
