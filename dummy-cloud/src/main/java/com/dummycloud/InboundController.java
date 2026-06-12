package com.dummycloud;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The cloud app's existing REST API, one endpoint per inbound type — exactly what the
 * proxy's {@code DeliverToCloud} activity posts to (catalog layer 1 defines these paths).
 */
@RestController
public class InboundController {

    private static final Logger log = LoggerFactory.getLogger(InboundController.class);

    private final ConfirmStore confirmStore;

    public InboundController(ConfirmStore confirmStore) {
        this.confirmStore = confirmStore;
    }

    @PostMapping("/api/command-result")
    public Map<String, String> commandResult(@RequestBody CanonicalMessage message) {
        return receive(message);
    }

    @PostMapping("/api/config-ack")
    public Map<String, String> configAck(@RequestBody CanonicalMessage message) {
        return receive(message);
    }

    @PostMapping("/api/report-upload")
    public Map<String, String> reportUpload(@RequestBody CanonicalMessage message) {
        return receive(message);
    }

    /**
     * Catch-all for any other inbound path. Part 3 lets operators define their own message
     * types with arbitrary cloud endpoints; this stand-in cloud app accepts whatever path they
     * point at (a real cloud app would add a typed handler). Spring routes the named endpoints
     * above to their specific handlers and everything else under {@code /api/} here.
     */
    @PostMapping("/api/**")
    public Map<String, String> anyOtherType(@RequestBody CanonicalMessage message) {
        return receive(message);
    }

    private Map<String, String> receive(CanonicalMessage message) {
        log.info("cloud received {} payload={}", message.activityId(), message.payload());
        confirmStore.add(message);
        return Map.of("status", "received");
    }
}
