package com.dummycloud;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Demo drivers: dispatch any outbound message type through the proxy. */
@RestController
public class DemoController {

    private final OutboundDispatcher dispatcher;
    private final ConfirmStore confirmStore;

    public DemoController(OutboundDispatcher dispatcher, ConfirmStore confirmStore) {
        this.dispatcher = dispatcher;
        this.confirmStore = confirmStore;
    }

    @PostMapping("/demo/command")
    public Map<String, Object> sendCommand(@RequestBody JsonNode body) {
        return dispatcher.dispatch(DeviceFleetCatalog.DEVICE_COMMAND, body);
    }

    @PostMapping("/demo/config")
    public Map<String, Object> pushConfig(@RequestBody JsonNode body) {
        return dispatcher.dispatch(DeviceFleetCatalog.CONFIG_UPDATE, body);
    }

    @PostMapping("/demo/report")
    public Map<String, Object> requestReport(@RequestBody JsonNode body) {
        return dispatcher.dispatch(DeviceFleetCatalog.REPORT_REQUEST, body);
    }

    /**
     * Generic dispatch for ANY catalog type — backs the catalog-driven DISPATCH tab. Body:
     * {@code {messageType, businessId, payload}} where {@code payload} is the codec-appropriate
     * string (CSV / XML / JSON); the proxy encodes it for the wire per the type's codec.
     */
    @PostMapping("/demo/dispatch")
    public Map<String, Object> dispatch(@RequestBody JsonNode body) {
        String messageType = text(body, "messageType");
        String businessId = text(body, "businessId");
        if (messageType.isBlank() || businessId.isBlank()) {
            throw new IllegalArgumentException("messageType and businessId are required");
        }
        JsonNode payloadNode = body.get("payload");
        String payload = payloadNode == null || payloadNode.isNull() ? ""
                : payloadNode.isTextual() ? payloadNode.textValue() : payloadNode.toString();
        return dispatcher.dispatch(new CanonicalMessage(messageType, businessId, payload));
    }

    @GetMapping("/demo/confirms")
    public List<CanonicalMessage> confirms() {
        return confirmStore.all();
    }

    private static String text(JsonNode body, String field) {
        JsonNode n = body.get(field);
        return n == null || n.isNull() ? "" : n.asText("");
    }
}
