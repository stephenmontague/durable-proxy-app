package com.proxyapp.controller;

import com.proxyapp.ingress.InboundGateway;
import com.proxyapp.ingress.IngressException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The HTTP ingress status contract — especially that a failure to reach Temporal is surfaced as a
 * retryable 503 (with Retry-After), not a 500, so a device backs off and retries instead of dropping
 * the message.
 */
class HttpIngressControllerTest {

    private final InboundGateway gateway = mock(InboundGateway.class);
    private final HttpIngressController controller = new HttpIngressController(gateway);

    @Test
    void temporalUnavailableIsRetryable503WithRetryAfter() {
        when(gateway.handle(any(), any(), any(), any())).thenThrow(new IngressException(
                IngressException.Reason.UPSTREAM_UNAVAILABLE, "could not enqueue to Temporal: UNAVAILABLE"));

        ResponseEntity<Map<String, Object>> res = controller.ingress("scan", new byte[]{1});

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    }

    @Test
    void acceptedIs202WithNoRetryAfter() {
        when(gateway.handle(any(), any(), any(), any()))
                .thenReturn(new InboundGateway.EnqueueResult("SCAN_EVENT-abc", false));

        ResponseEntity<Map<String, Object>> res = controller.ingress("scan", new byte[]{1});

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
    }

    @Test
    void unknownChannelIs404AndNotRetryable() {
        when(gateway.handle(any(), any(), any(), any())).thenThrow(new IngressException(
                IngressException.Reason.UNKNOWN_CHANNEL, "no inbound binding for HTTP channel 'nope'"));

        ResponseEntity<Map<String, Object>> res = controller.ingress("nope", new byte[]{1});

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
    }
}
