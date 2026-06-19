package com.proxyapp.routing.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Optional TCP wire-protocol settings, configurable per device ({@link EdgeConfig}) with
 * per-binding override ({@link RouteBinding}). Absent everywhere (null) = legacy
 * behavior: EOF framing, {@code "ACK {id}\n"} / {@code "ERR {reason} {msg}\n"} inbound
 * replies, and a {@code startsWith("ACK")} outbound check.
 *
 * <p>All string fields use {@link WireString} escape syntax (e.g. {@code <VT>},
 * {@code \x1c}) and decode to bytes via ISO-8859-1. Reply templates are written
 * <b>verbatim</b> after decoding + placeholder substitution — if your protocol frames its
 * acks, embed the framing characters in the template yourself.
 *
 * @param startDelimiter frame start (nullable; requires {@code endDelimiter} when set)
 * @param endDelimiter   frame end (nullable; null = EOF framing, one message/connection)
 * @param ackReply       inbound success reply template; {@code {activityId}} substituted
 * @param nakReply       inbound failure reply template; {@code {reason}} substituted
 * @param expectedAck    outbound: bytes that must <i>appear</i> in the device reply
 *                       (contains-match, so {@code ACK} also matches a framed ack)
 * @param awaitReply     outbound: null/true = wait for the reply; false = fire-and-forget.
 *                       Boxed deliberately — a primitive would deserialize missing JSON
 *                       fields to false and silently flip legacy configs to fire-and-forget.
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
