package com.dummyedge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * HTTP channel of the device: receives DEVICE_COMMAND on /commands and auto-pushes the
 * paired COMMAND_RESULT back to the proxy's /command-result channel.
 */
@RestController
public class HttpDeviceController {

    private static final Logger log = LoggerFactory.getLogger(HttpDeviceController.class);

    private final ReceivedStore receivedStore;
    private final ConfirmPusher confirmPusher;
    private final EdgeProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpDeviceController(ReceivedStore receivedStore, ConfirmPusher confirmPusher,
                                EdgeProperties properties) {
        this.receivedStore = receivedStore;
        this.confirmPusher = confirmPusher;
        this.properties = properties;
    }

    @PostMapping("/commands")
    public Map<String, String> receiveCommand(@RequestBody JsonNode body) {
        log.info("device received command: {}", body);
        receivedStore.add("HTTP", "/commands", body.toString());

        String commandId = body.path("commandId").asText("");
        // The xml profile makes this device speak XML; the proxy's xml codec pulls the
        // business id out of the <commandId> element (just demo-command-http-xml).
        String payload = properties.xmlConfirms()
                ? "<commandResult><commandId>" + commandId + "</commandId><status>DONE</status></commandResult>"
                : jsonConfirm(body);
        confirmPusher.pushHttpCommandResult(payload);
        return Map.of("status", "accepted");
    }

    private String jsonConfirm(JsonNode body) {
        ObjectNode confirm = mapper.createObjectNode();
        confirm.set("commandId", body.get("commandId"));
        confirm.put("status", "DONE");
        confirm.set("action", body.get("action"));
        return confirm.toString();
    }

    @GetMapping("/received")
    public List<Map<String, String>> received() {
        return receivedStore.all();
    }
}
