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

    @GetMapping("/demo/confirms")
    public List<CanonicalMessage> confirms() {
        return confirmStore.all();
    }
}
