package com.proxyapp.routing.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Opaque key for a named, directional message flow. The core never interprets the name;
 * everything domain-specific about it lives in the {@link MessageCatalog}.
 */
public record MessageType(@JsonValue String value) {

    @JsonCreator
    public static MessageType of(String value) {
        return new MessageType(value);
    }

    public MessageType {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("message type must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
