package com.proxyapp.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * The canonical typed message the proxy moves in both directions. The payload stays an opaque string
 * (typically JSON) — the core never interprets it; codecs do. {@code businessId} is the dedup handle:
 * Activity ID is {@code {messageType}-{businessId}}.
 */
public record CanonicalMessage(String messageType, String businessId, String payload) {

    @JsonIgnore
    public String activityId() {
        return messageType + "-" + businessId;
    }
}
