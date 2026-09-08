package com.proxyapp.routing.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Optional TCP wire settings, per device ({@link EdgeConfig}) with per-binding override
 * ({@link RouteBinding}). Absent = legacy behavior: EOF framing, {@code "ACK {id}\n"} /
 * {@code "ERR {reason} {msg}\n"} inbound replies, {@code startsWith("ACK")} outbound check.
 * String fields use WireString escapes and decode via ISO-8859-1; reply templates are written
 * verbatim after substitution, so frame your acks yourself. {@code expectedAck} is a contains-match;
 * {@code awaitReply} is boxed so missing JSON doesn't default to fire-and-forget.
 */
public record TcpProtocol(String startDelimiter, String endDelimiter, String ackReply,
                          String nakReply, String expectedAck, Boolean awaitReply) {

    @JsonIgnore
    public boolean shouldAwaitReply() {
        return awaitReply == null || awaitReply;
    }

    @JsonIgnore
    public boolean isFramed() {
        return endDelimiter != null;
    }
}
