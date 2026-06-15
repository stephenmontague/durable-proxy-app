package com.proxyapp.routing;

/**
 * One message type as defined by the cloud-side profile. Shipped/managed by the cloud-app
 * operator; the customer never edits this layer.
 *
 * @param type            the message type key
 * @param direction       flow direction
 * @param codec           codec name used on the edge side, e.g. "json"
 * @param cloudEndpoint   for EDGE_TO_CLOUD types: path on the cloud base URL the proxy posts to
 * @param businessIdField field inside the decoded payload that carries the business id
 *                        (dedup handle); null falls back to a payload hash
 * @param allowDuplicates when true, identical inbound pushes are NOT deduped — every push becomes
 *                        its own delivery. For event/telemetry streams where two byte-identical
 *                        frames are two real observations, not a retransmit. Default false (dedup on).
 */
public record CatalogEntry(MessageType type, Direction direction, String codec,
                           String cloudEndpoint, String businessIdField, boolean allowDuplicates) {

    /** Backward-compatible: dedup on (allowDuplicates = false), the historical behavior. */
    public CatalogEntry(MessageType type, Direction direction, String codec,
                        String cloudEndpoint, String businessIdField) {
        this(type, direction, codec, cloudEndpoint, businessIdField, false);
    }
}
