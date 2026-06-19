package com.proxyapp.control.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.proxyapp.routing.model.CatalogEntry;
import com.proxyapp.routing.model.Direction;
import com.proxyapp.routing.model.MessageType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CatalogEntryDto crosses the Temporal signal/query boundary as JSON, so the {@code allowDuplicates}
 * flag must round-trip cleanly — through {@code from}/{@code toCatalogEntry} and through Jackson,
 * including the backward-compatible 5-arg constructor and JSON that predates the field.
 */
class CatalogEntryDtoTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void allowDuplicatesSurvivesFlattenAndRehydrate() {
        CatalogEntry entry = new CatalogEntry(
                MessageType.of("SCAN_EVENT"), Direction.EDGE_TO_CLOUD, "raw", "/api/scan", null, true);

        CatalogEntryDto dto = CatalogEntryDto.from(entry);
        assertThat(dto.allowDuplicates()).isTrue();

        CatalogEntry restored = dto.toCatalogEntry();
        assertThat(restored.allowDuplicates()).isTrue();
        assertThat(restored).isEqualTo(entry);
    }

    @Test
    void legacyConstructorDefaultsToDedupOn() {
        CatalogEntryDto dto = new CatalogEntryDto("DEVICE_COMMAND", "CLOUD_TO_EDGE", "json", null, "id");
        assertThat(dto.allowDuplicates()).isFalse();
        assertThat(dto.toCatalogEntry().allowDuplicates()).isFalse();
    }

    @Test
    void jacksonRoundTripsTheFlag() throws Exception {
        CatalogEntryDto original =
                new CatalogEntryDto("METER_READING", "EDGE_TO_CLOUD", "xml", "/api/meter", "meter", true);

        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("\"allowDuplicates\":true");

        CatalogEntryDto restored = mapper.readValue(json, CatalogEntryDto.class);
        assertThat(restored).isEqualTo(original);
    }

    @Test
    void jacksonDefaultsMissingFlagToFalse() throws Exception {
        // JSON written before the field existed must still deserialize (dedup on).
        String legacyJson = "{\"type\":\"REPORT\",\"direction\":\"EDGE_TO_CLOUD\","
                + "\"codec\":\"json\",\"cloudEndpoint\":\"/api/report\",\"businessIdField\":\"id\"}";

        CatalogEntryDto restored = mapper.readValue(legacyJson, CatalogEntryDto.class);
        assertThat(restored.allowDuplicates()).isFalse();
    }
}
