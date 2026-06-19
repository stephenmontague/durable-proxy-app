package com.proxyapp.codec;

import com.proxyapp.model.CanonicalMessage;
import com.proxyapp.routing.model.CatalogEntry;
import com.proxyapp.routing.model.Direction;
import com.proxyapp.routing.model.MessageType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RawCodecTest {

    private final RawCodec codec = new RawCodec();
    // businessIdField is set but must be ignored — raw never parses the body.
    private final CatalogEntry entry = new CatalogEntry(MessageType.of("SENSOR_BLOB"),
            Direction.EDGE_TO_CLOUD, "raw", "/api/sensor", "ignored");

    @Test
    void encodePassesPayloadThroughUnchanged() {
        CanonicalMessage message = new CanonicalMessage("SENSOR_BLOB", "x", "12,34,56|raw|bytes");
        assertThat(codec.encode(message))
                .isEqualTo("12,34,56|raw|bytes".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void decodeUsesContentHashForDedupAndIsStable() {
        byte[] raw = "fixed-width   payload   0042".getBytes(StandardCharsets.UTF_8);
        CanonicalMessage first = codec.decode(entry, raw);
        CanonicalMessage second = codec.decode(entry, raw);
        assertThat(first.messageType()).isEqualTo("SENSOR_BLOB");
        assertThat(first.businessId()).isNotBlank().isEqualTo(second.businessId());

        CanonicalMessage other = codec.decode(entry, "different".getBytes(StandardCharsets.UTF_8));
        assertThat(other.businessId()).isNotEqualTo(first.businessId());
    }

    @Test
    void decodePreservesPayloadVerbatimWithoutTrimming() {
        byte[] raw = "  leading and trailing space  ".getBytes(StandardCharsets.UTF_8);
        assertThat(codec.decode(entry, raw).payload()).isEqualTo("  leading and trailing space  ");
    }
}
