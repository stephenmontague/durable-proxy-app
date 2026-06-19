package com.proxyapp.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proxyapp.model.CanonicalMessage;
import com.proxyapp.routing.model.CatalogEntry;

import java.nio.charset.StandardCharsets;

/**
 * Default codec: the payload is already JSON. Encoding sends the payload as-is; decoding
 * extracts the business id from the catalog-configured field, falling back to a content
 * hash so duplicate pushes of the same payload still dedup.
 */
public class JsonCodec implements MessageCodec {

    public static final String NAME = "json";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public byte[] encode(CanonicalMessage message) {
        return message.payload().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public CanonicalMessage decode(CatalogEntry entry, byte[] raw) {
        String payload = new String(raw, StandardCharsets.UTF_8).trim();
        String businessId = null;
        if (entry.businessIdField() != null) {
            try {
                JsonNode node = mapper.readTree(payload).get(entry.businessIdField());
                if (node != null && !node.isNull()) {
                    businessId = node.asText();
                }
            } catch (Exception e) {
                // not parseable JSON — fall through to the content hash
            }
        }
        if (businessId == null || businessId.isBlank()) {
            businessId = ContentHash.of(payload);
        }
        return new CanonicalMessage(entry.type().value(), businessId, payload);
    }
}
