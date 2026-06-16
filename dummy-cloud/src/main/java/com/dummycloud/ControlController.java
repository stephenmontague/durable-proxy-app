package com.dummycloud;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Control-plane front door for the management UI. Every change is a Signal to the proxy's control
 * workflow (Temporal — the source of truth); on an accepted change the new config is persisted to
 * the H2 read model, and {@code GET /control/state} serves that read model so the UI hydrates
 * without a Temporal Query. The cloud never talks to the proxy directly.
 *
 * <p>Lifecycle signals (requestRestart/requestShutdown) are NOT here — they don't change config, so
 * the UI fires them straight at the workflow and reads live status from Temporal.
 */
@RestController
public class ControlController {

    private final ConfigStateService configState;

    public ControlController(ConfigStateService configState) {
        this.configState = configState;
    }

    @PostMapping("/control/enable")
    public Map<String, Object> enable() {
        return configState.applyChange("enable", null);
    }

    @PostMapping("/control/disable")
    public Map<String, Object> disable() {
        return configState.applyChange("disable", null);
    }

    /** Body: JSON array of EdgeConfig (full device-list replace). */
    @PostMapping("/control/apply-config")
    public Map<String, Object> applyConfig(@RequestBody JsonNode devices) {
        return configState.applyChange("applyConfig", devices);
    }

    /** Body: a single EdgeConfig (add or replace one device). */
    @PostMapping("/control/upsert-device")
    public Map<String, Object> upsertDevice(@RequestBody JsonNode device) {
        return configState.applyChange("upsertDevice", device);
    }

    @PostMapping("/control/remove-device/{deviceId}")
    public Map<String, Object> removeDevice(@PathVariable String deviceId) {
        return configState.applyChange("removeDevice", deviceId);
    }

    /** Body: JSON array of CatalogEntryDto — replaces the whole message catalog. */
    @PostMapping("/control/import-catalog")
    public Map<String, Object> importCatalog(@RequestBody JsonNode entries) {
        return configState.applyChange("importCatalog", entries);
    }

    /** Body: a single CatalogEntryDto — adds or replaces one message type. */
    @PostMapping("/control/upsert-message-type")
    public Map<String, Object> upsertMessageType(@RequestBody JsonNode entry) {
        return configState.applyChange("upsertMessageType", entry);
    }

    @PostMapping("/control/remove-message-type/{type}")
    public Map<String, Object> removeMessageType(@PathVariable String type) {
        return configState.applyChange("removeMessageType", type);
    }

    /** Desired config from the H2 read model — no Temporal Query. */
    @GetMapping("/control/state")
    public Map<String, Object> state() {
        return Map.of("state", configState.readState());
    }
}
