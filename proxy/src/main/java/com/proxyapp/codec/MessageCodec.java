package com.proxyapp.codec;

import com.proxyapp.model.CanonicalMessage;
import com.proxyapp.routing.model.CatalogEntry;

/**
 * Translates between the edge target's wire format and the canonical message. The message
 * type is supplied by the channel (never sniffed from the payload); the codec's decode job
 * is payload normalization + business-id extraction.
 */
public interface MessageCodec {

    String name();

    /** Canonical -> edge wire bytes. */
    byte[] encode(CanonicalMessage message);

    /** Edge wire bytes -> canonical, for the type the channel identified. */
    CanonicalMessage decode(CatalogEntry entry, byte[] raw);
}
