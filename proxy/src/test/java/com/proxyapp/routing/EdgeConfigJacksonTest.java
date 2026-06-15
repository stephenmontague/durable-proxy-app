package com.proxyapp.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EdgeConfig (with a full TcpSession) crosses the Temporal signal/query boundary as JSON, so
 * Jackson must round-trip the nested records cleanly — including past EdgeConfig's extra
 * convenience constructors, which must not disturb the canonical-constructor pick for records.
 */
class EdgeConfigJacksonTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void edgeConfigWithPersistentSessionRoundTrips() throws Exception {
        TcpSession session = new TcpSession(
                TcpSession.Mode.PERSISTENT, TcpSession.Role.CLIENT, 9001, null, null,
                new TcpSession.Heartbeat(30, "<VT>PING<FS>", "PONG", 5000, 60, 3),
                new TcpSession.Correlation(TcpSession.Correlation.Strategy.CORRELATION_ID, "msgId", null),
                "CONFIG_ACK");
        EdgeConfig original = new EdgeConfig("gateway-1", null, "10.0.0.5", null, null, null,
                List.of(new RouteBinding(MessageType.of("CONFIG_UPDATE"), Transport.TCP, Channel.port(9001))),
                null, session);

        EdgeConfig restored = mapper.readValue(mapper.writeValueAsString(original), EdgeConfig.class);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.tcpSession().isPersistent()).isTrue();
        assertThat(restored.tcpSession().heartbeat().hasOutboundPing()).isTrue();
        assertThat(restored.tcpSession().heartbeat().hasInboundWatchdog()).isTrue();
    }

    @Test
    void legacyEdgeConfigWithoutSessionRoundTripsWithNullSession() throws Exception {
        EdgeConfig original = new EdgeConfig("gateway-1", "http://e", "10.0.0.5", null, null, null,
                List.of(new RouteBinding(MessageType.of("DEVICE_COMMAND"), Transport.HTTP, Channel.path("/commands"))));

        EdgeConfig restored = mapper.readValue(mapper.writeValueAsString(original), EdgeConfig.class);

        assertThat(restored.tcpSession()).isNull();
        assertThat(restored).isEqualTo(original);
    }
}
