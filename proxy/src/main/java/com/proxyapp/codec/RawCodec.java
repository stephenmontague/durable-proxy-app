package com.proxyapp.codec;

import com.proxyapp.model.CanonicalMessage;
import com.proxyapp.routing.model.CatalogEntry;

import java.nio.charset.StandardCharsets;

/**
 * Opaque passthrough codec: the proxy carries the payload byte-for-byte (as UTF-8 text) and
 * never parses it. There's no structure to read a business id from, so dedup always uses a
 * content hash. Pick this for formats the proxy shouldn't interpret — fixed-width, delimited,
 * or any vendor blob — where you just want it relayed.
 */
public class RawCodec implements MessageCodec {

    public static final String NAME = "raw";

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
        // No trim: raw means raw. businessIdField is ignored — nothing parses the body.
        String payload = new String(raw, StandardCharsets.UTF_8);
        return new CanonicalMessage(entry.type().value(), ContentHash.of(payload), payload);
    }
}
