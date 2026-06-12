package com.proxyapp.codec;

import com.proxyapp.model.CanonicalMessage;
import com.proxyapp.routing.CatalogEntry;
import com.proxyapp.routing.Direction;
import com.proxyapp.routing.MessageType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class JsonCodecTest {

    private final JsonCodec codec = new JsonCodec();
    private final CatalogEntry entry = new CatalogEntry(MessageType.of("COMMAND_RESULT"),
            Direction.EDGE_TO_CLOUD, "json", "/api/command-result", "commandId");

    @Test
    void encodeSendsPayloadAsIs() {
        CanonicalMessage message = new CanonicalMessage("DEVICE_COMMAND", "ORD-1", "{\"commandId\":\"ORD-1\"}");
        assertThat(codec.encode(message)).isEqualTo("{\"commandId\":\"ORD-1\"}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void decodeExtractsBusinessIdFromConfiguredField() {
        byte[] raw = "{\"commandId\":\"ORD-42\",\"status\":\"PICKED\"}".getBytes(StandardCharsets.UTF_8);
        CanonicalMessage message = codec.decode(entry, raw);
        assertThat(message.messageType()).isEqualTo("COMMAND_RESULT");
        assertThat(message.businessId()).isEqualTo("ORD-42");
        assertThat(message.activityId()).isEqualTo("COMMAND_RESULT-ORD-42");
    }

    @Test
    void decodeFallsBackToContentHashSoDuplicatesStillDedup() {
        byte[] raw = "not json at all".getBytes(StandardCharsets.UTF_8);
        CanonicalMessage first = codec.decode(entry, raw);
        CanonicalMessage second = codec.decode(entry, raw);
        assertThat(first.businessId()).isNotBlank().isEqualTo(second.businessId());

        CanonicalMessage other = codec.decode(entry, "different".getBytes(StandardCharsets.UTF_8));
        assertThat(other.businessId()).isNotEqualTo(first.businessId());
    }

    @Test
    void decodeFallsBackWhenFieldMissing() {
        byte[] raw = "{\"something\":\"else\"}".getBytes(StandardCharsets.UTF_8);
        CanonicalMessage message = codec.decode(entry, raw);
        assertThat(message.businessId()).isNotBlank();
    }
}
