package com.proxyapp.ingress;

import com.proxyapp.model.CanonicalMessage;
import com.proxyapp.routing.CatalogEntry;
import com.proxyapp.routing.Direction;
import com.proxyapp.routing.MessageType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dedup decision: {@link InboundGateway#activityId} controls whether identical inbound pushes
 * collapse to one Temporal activity (default) or each become their own delivery (allowDuplicates).
 * Tested directly so the dedup contract is locked without standing up a Temporal client.
 */
class InboundGatewayTest {

    private static final CanonicalMessage MESSAGE =
            new CanonicalMessage("SCAN_EVENT", "abc123", "SCAN,PKG-1,LANE-3");

    @Test
    void dedupByDefaultKeysOnTypeAndBusinessId() {
        CatalogEntry dedup = new CatalogEntry(
                MessageType.of("SCAN_EVENT"), Direction.EDGE_TO_CLOUD, "raw", "/api/scan", null);

        // Same payload -> same activity id -> Temporal's REJECT_DUPLICATE collapses repeats to one.
        assertThat(InboundGateway.activityId(dedup, MESSAGE)).isEqualTo("SCAN_EVENT-abc123");
        assertThat(InboundGateway.activityId(dedup, MESSAGE))
                .isEqualTo(InboundGateway.activityId(dedup, MESSAGE));
    }

    @Test
    void allowDuplicatesMakesEveryPushUnique() {
        CatalogEntry stream = new CatalogEntry(
                MessageType.of("SCAN_EVENT"), Direction.EDGE_TO_CLOUD, "raw", "/api/scan", null, true);

        String first = InboundGateway.activityId(stream, MESSAGE);
        String second = InboundGateway.activityId(stream, MESSAGE);

        // The natural id is still the prefix (traceable), but each push gets a distinct suffix so
        // byte-identical frames are delivered individually rather than deduped.
        assertThat(first).startsWith("SCAN_EVENT-abc123-");
        assertThat(second).startsWith("SCAN_EVENT-abc123-");
        assertThat(first).isNotEqualTo(second);
    }
}
