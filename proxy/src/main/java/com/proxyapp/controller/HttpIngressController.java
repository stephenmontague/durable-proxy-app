package com.proxyapp.controller;
import com.proxyapp.ingress.InboundGateway;
import com.proxyapp.ingress.IngressException;

import com.proxyapp.routing.model.Transport;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * HTTP ingress: the request path is the channel. Catch-all mapping — exact mappings such
 * as the admin endpoints take precedence, everything else is looked up in the route table.
 */
@RestController
public class HttpIngressController {

    private final InboundGateway gateway;

    public HttpIngressController(InboundGateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/{*path}")
    public ResponseEntity<Map<String, Object>> ingress(@PathVariable("path") String path,
                                                       @RequestBody byte[] body) {
        try {
            InboundGateway.EnqueueResult result =
                    gateway.handle(Transport.HTTP, path, null, body);
            return ResponseEntity.accepted().body(Map.of(
                    "activityId", result.activityId(),
                    "duplicate", result.duplicate()));
        } catch (IngressException e) {
            HttpStatus status = switch (e.reason()) {
                case DISABLED -> HttpStatus.SERVICE_UNAVAILABLE;
                case UNKNOWN_CHANNEL -> HttpStatus.NOT_FOUND;
                case UNRESOLVED_TYPE -> HttpStatus.BAD_REQUEST;
            };
            return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
        }
    }
}
