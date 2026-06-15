package com.proxyapp.routing;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Optional per-device persistent-TCP-session config. Absent (null) or {@link Mode#PER_MESSAGE}
 * = today's connect-per-message {@link TcpConnector}-style behavior. {@link Mode#PERSISTENT}
 * keeps a single socket warm with heartbeats; outbound messages still ride durable Temporal
 * activities (see {@code docs/persistent-tcp-sessions.md}) — only the in-activity transport call
 * changes.
 *
 * <p>Per device because the socket is per device. Frame delimiters reuse the device/binding
 * {@link TcpProtocol}; this record adds role, endpoint, liveness, and correlation. It crosses the
 * Temporal signal/query boundary as JSON, so all fields are plain nullable wrappers.
 *
 * @param mode        PER_MESSAGE (default) or PERSISTENT
 * @param role        who dials: CLIENT (proxy dials the device) or SERVER (device dials in);
 *                    required when PERSISTENT
 * @param port        CLIENT: the device port the proxy dials (host comes from {@link EdgeConfig#host()})
 * @param listenPort  SERVER: the local port the proxy listens on for this device
 * @param handshakeId SERVER alternative to a dedicated port: identify the device by its
 *                    first-frame handshake when several devices share one listen port
 * @param heartbeat   liveness config (outbound ping and/or inbound watchdog)
 * @param correlation request/response matching over the one shared socket; null = single-in-flight
 */
public record TcpSession(Mode mode, Role role, Integer port, Integer listenPort,
                         String handshakeId, Heartbeat heartbeat, Correlation correlation) {

    public enum Mode { PER_MESSAGE, PERSISTENT }

    public enum Role { CLIENT, SERVER }

    @JsonIgnore
    public boolean isPersistent() {
        return mode == Mode.PERSISTENT;
    }

    /**
     * Liveness for a persistent session. A PERSISTENT session must define at least one of: an
     * <b>outbound ping</b> ({@code sendIntervalSec} + {@code sendPayload}, optionally awaiting
     * {@code expectReply} within {@code replyTimeoutMs}) and/or an <b>inbound watchdog</b>
     * ({@code expectInboundSec}). {@code missThreshold} consecutive misses flip the link DOWN.
     * {@code sendPayload}/{@code expectReply} use {@link WireString} escape syntax.
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
     * How a send's reply is matched to its request on the shared socket. {@code SINGLE_IN_FLIGHT}
     * (default) allows one outstanding send at a time and contains-matches the configured ack;
     * {@code CORRELATION_ID}/{@code SEQUENCE} read {@code field}/{@code delimiter} from the frame.
     * Consumed in a later phase; modeled here so the config contract is stable.
     */
    public record Correlation(Strategy strategy, String field, String delimiter) {
        public enum Strategy { SINGLE_IN_FLIGHT, CORRELATION_ID, SEQUENCE }
    }
}
