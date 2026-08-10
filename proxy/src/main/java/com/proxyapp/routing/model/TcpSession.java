package com.proxyapp.routing.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Optional per-device persistent-TCP-session config. Absent or {@link Mode#PER_MESSAGE} = today's
 * connect-per-message behavior; {@link Mode#PERSISTENT} keeps one socket warm with heartbeats.
 * Frame delimiters reuse the device/binding {@link TcpProtocol}; this adds role, endpoint, liveness,
 * and correlation. CLIENT dials {@code port} on {@link EdgeConfig#host()}; SERVER listens on
 * {@code listenPort}, using {@code handshakeId} to tell devices apart when they share one port.
 * {@code inboundType} and {@code resolver} are mutually exclusive ways to type unsolicited frames.
 * Crosses the Temporal boundary as JSON, so every field is a nullable wrapper.
 */
public record TcpSession(Mode mode, Role role, Integer port, Integer listenPort,
                         String handshakeId, Heartbeat heartbeat, Correlation correlation,
                         String inboundType, ResolverConfig resolver) {

    /** A session with no unsolicited-inbound typing configured — neither inboundType nor resolver. */
    public TcpSession(Mode mode, Role role, Integer port, Integer listenPort,
                      String handshakeId, Heartbeat heartbeat, Correlation correlation) {
        this(mode, role, port, listenPort, handshakeId, heartbeat, correlation, null, null);
    }

    public enum Mode { PER_MESSAGE, PERSISTENT }

    public enum Role { CLIENT, SERVER }

    @JsonIgnore
    public boolean isPersistent() {
        return mode == Mode.PERSISTENT;
    }

    /**
     * Liveness for a persistent session; at least one of outbound ping ({@code sendIntervalSec} +
     * {@code sendPayload}) or inbound watchdog ({@code expectInboundSec}) is required.
     * {@code missThreshold} consecutive misses flip the link DOWN. Payloads use WireString escapes.
     */
    public record Heartbeat(Integer sendIntervalSec, String sendPayload, String expectReply,
                            Integer replyTimeoutMs, Integer expectInboundSec, Integer missThreshold) {
        @JsonIgnore
        public boolean hasOutboundPing() {
            return sendIntervalSec != null;
        }

        @JsonIgnore
        public boolean hasInboundWatchdog() {
            return expectInboundSec != null;
        }
    }

    /**
     * How a send's reply is matched to its request. {@code SINGLE_IN_FLIGHT} (default) allows one
     * outstanding send and contains-matches the configured ack. {@code CORRELATION_ID} and
     * {@code SEQUENCE} are reserved and not yet honored — configuring one still behaves as
     * {@code SINGLE_IN_FLIGHT}.
     */
    public record Correlation(Strategy strategy, String field, String delimiter) {
        public enum Strategy { SINGLE_IN_FLIGHT, CORRELATION_ID, SEQUENCE }
    }
}
