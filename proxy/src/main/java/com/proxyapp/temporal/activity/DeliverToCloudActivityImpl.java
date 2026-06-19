package com.proxyapp.temporal.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.proxyapp.config.ProxyProperties;
import com.proxyapp.model.CanonicalMessage;
import com.proxyapp.routing.model.CatalogEntry;
import com.proxyapp.routing.model.MessageType;
import com.proxyapp.routing.RoutingState;
import io.temporal.spring.boot.ActivityImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
@ActivityImpl(taskQueues = "${proxy.task-queue}")
public class DeliverToCloudActivityImpl implements DeliverToCloudActivity {

    private static final Logger log = LoggerFactory.getLogger(DeliverToCloudActivityImpl.class);

    private final ProxyProperties properties;
    private final RoutingState routingState;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public DeliverToCloudActivityImpl(ProxyProperties properties, RoutingState routingState) {
        this.properties = properties;
        this.routingState = routingState;
    }

    @Override
    public void deliver(CanonicalMessage message) {
        // Read the catalog from the live route table — the Reconciler rebuilds it from control
        // state each reconcile, so the cloudEndpoint reflects the operator's latest catalog edits.
        CatalogEntry entry = routingState.table().catalog()
                .require(MessageType.of(message.messageType()));
        String url = properties.cloud().baseUrl() + entry.cloudEndpoint();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(message)))
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("cloud endpoint " + url + " returned HTTP "
                        + response.statusCode() + ": " + response.body());
            }
        } catch (IOException e) {
            throw new IllegalStateException("POST to cloud endpoint " + url + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("POST to cloud endpoint " + url + " interrupted", e);
        }
        log.info("delivered {} to cloud endpoint {}", message.activityId(), url);
    }
}
