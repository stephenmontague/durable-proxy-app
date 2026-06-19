package com.proxyapp.connector;
import com.proxyapp.connector.model.ChannelTarget;

import com.proxyapp.routing.model.Transport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** POSTs the payload to the device URL; any non-2xx fails the send (and the activity retries). */
public class HttpConnector implements Connector {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public Transport transport() {
        return Transport.HTTP;
    }

    @Override
    public void send(ChannelTarget target, byte[] payload) {
        ChannelTarget.HttpTarget http = (ChannelTarget.HttpTarget) target;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(http.url()))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ConnectorSendException("HTTP " + response.statusCode()
                        + " from " + http.url() + ": " + response.body());
            }
        } catch (IOException e) {
            throw new ConnectorSendException("HTTP send to " + http.url() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectorSendException("HTTP send to " + http.url() + " interrupted", e);
        }
    }
}
