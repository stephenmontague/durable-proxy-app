package com.proxyapp.control;

import com.proxyapp.routing.CatalogEntry;
import com.proxyapp.routing.Direction;
import com.proxyapp.routing.MessageType;

/**
 * A message-type definition as it travels through the control workflow: a flat,
 * Jackson-friendly mirror of {@link CatalogEntry} using plain strings (no {@link MessageType}
 * wrapper or {@link Direction} enum) so it serializes cleanly into workflow state and across
 * the UI's signal/query boundary.
 *
 * <p>Part 3 made the catalog operator-editable: these live in {@code ProxyControlState} instead
 * of being hardcoded in the profile. The proxy converts them back to {@link CatalogEntry} when
 * it rebuilds its {@code MessageCatalog} on reconcile.
 *
 * @param type            message type name, e.g. {@code "DEVICE_COMMAND"}
 * @param direction       {@code "CLOUD_TO_EDGE"} or {@code "EDGE_TO_CLOUD"}
 * @param codec           codec name: {@code "json"}, {@code "xml"}, or {@code "raw"}
 * @param cloudEndpoint   for EDGE_TO_CLOUD types, the path the proxy POSTs to; null otherwise
 * @param businessIdField payload field carrying the dedup id; null falls back to a content hash
 */
public record CatalogEntryDto(String type, String direction, String codec,
                              String cloudEndpoint, String businessIdField) {

    /** Flatten a catalog entry for transport in workflow state. */
    public static CatalogEntryDto from(CatalogEntry entry) {
        return new CatalogEntryDto(entry.type().value(), entry.direction().name(),
                entry.codec(), entry.cloudEndpoint(), entry.businessIdField());
    }

    /**
     * Rehydrate into a routing {@link CatalogEntry}. Assumes the entry already passed
     * {@link CatalogValidator} (the control workflow validates before storing), so the
     * direction string is a valid enum constant.
     */
    public CatalogEntry toCatalogEntry() {
        return new CatalogEntry(MessageType.of(type), Direction.valueOf(direction),
                codec, cloudEndpoint, businessIdField);
    }
}
