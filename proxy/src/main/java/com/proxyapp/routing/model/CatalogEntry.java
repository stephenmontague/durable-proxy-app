package com.proxyapp.routing.model;

/**
 * One message type as defined by the cloud-side profile; the customer never edits this layer.
 * {@code businessIdField} is the dedup handle inside the decoded payload; null falls back to a
 * payload hash. {@code allowDuplicates} skips dedup, for telemetry streams where two identical
 * frames are two real observations rather than a retransmit.
 */
public record CatalogEntry(MessageType type, Direction direction, String codec,
                           String cloudEndpoint, String businessIdField, boolean allowDuplicates) {

    /** Default: dedup on. */
    public CatalogEntry(MessageType type, Direction direction, String codec,
                        String cloudEndpoint, String businessIdField) {
        this(type, direction, codec, cloudEndpoint, businessIdField, false);
    }
}
