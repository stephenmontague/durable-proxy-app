package com.proxyapp.control.model;

import com.proxyapp.routing.model.CatalogEntry;
import com.proxyapp.routing.model.Direction;
import com.proxyapp.routing.model.MessageType;

/**
 * A message-type definition as it travels through the control workflow: a flat,
 * Jackson-friendly mirror of {@link CatalogEntry} using plain strings (no {@link MessageType}
 * wrapper or {@link Direction} enum) so it serializes cleanly into workflow state and across the
 * signal/update/query boundary a control client talks to.
 *
 * <p>This is the wire shape of the catalog: the operator-editable catalog lives in
 * {@code ProxyControlState} as a list of these rather than being fixed in the boot profile, and
 * the proxy converts them back to {@link CatalogEntry} when it rebuilds its {@code MessageCatalog}
 * on reconcile. A client managing message types sends and receives exactly these fields.
 *
 * @param type            message type name, e.g. {@code "DEVICE_COMMAND"}
 * @param direction       {@code "CLOUD_TO_EDGE"} or {@code "EDGE_TO_CLOUD"}
 * @param codec           codec name: {@code "json"}, {@code "xml"}, or {@code "raw"}
 * @param cloudEndpoint   for EDGE_TO_CLOUD types, the path the proxy POSTs to; null otherwise
 * @param businessIdField payload field carrying the dedup id; null falls back to a content hash
 * @param allowDuplicates when true, identical inbound pushes are delivered individually instead of
 *                        deduped (event/telemetry streams); default false. See {@link CatalogEntry}.
 */
public record CatalogEntryDto(String type, String direction, String codec,
                              String cloudEndpoint, String businessIdField, boolean allowDuplicates) {

    /** Default: dedup on (allowDuplicates = false). Also the shape stored state deserializes into. */
    public CatalogEntryDto(String type, String direction, String codec,
                           String cloudEndpoint, String businessIdField) {
        this(type, direction, codec, cloudEndpoint, businessIdField, false);
    }

    /** Flatten a catalog entry for transport in workflow state. */
    public static CatalogEntryDto from(CatalogEntry entry) {
        return new CatalogEntryDto(entry.type().value(), entry.direction().name(),
                entry.codec(), entry.cloudEndpoint(), entry.businessIdField(), entry.allowDuplicates());
    }

    /**
     * Rehydrate into a routing {@link CatalogEntry}. Assumes the entry already passed
     * {@link com.proxyapp.control.CatalogValidator CatalogValidator} (the control workflow validates before storing), so the
     * direction string is a valid enum constant.
     */
    public CatalogEntry toCatalogEntry() {
        return new CatalogEntry(MessageType.of(type), Direction.valueOf(direction),
                codec, cloudEndpoint, businessIdField, allowDuplicates);
    }
}
