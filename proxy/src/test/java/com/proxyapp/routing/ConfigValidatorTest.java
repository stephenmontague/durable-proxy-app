package com.proxyapp.routing;
import com.proxyapp.routing.model.Channel;
import com.proxyapp.routing.model.EdgeConfig;
import com.proxyapp.routing.model.MessageType;
import com.proxyapp.routing.model.ResolverConfig;
import com.proxyapp.routing.model.RouteBinding;
import com.proxyapp.routing.model.TcpProtocol;
import com.proxyapp.routing.model.TcpSession;
import com.proxyapp.routing.model.Transport;

import com.proxyapp.profile.DeviceFleetProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigValidatorTest {

    private final MessageCatalog catalog = new DeviceFleetProfile().catalog();
    private final List<Integer> pool = IntStream.rangeClosed(6000, 6010).boxed().toList();

    private EdgeConfig validDevice() {
        return new EdgeConfig("gateway-1", "http://edge:8082", "10.0.0.5", 2222, "u", "p", List.of(
                new RouteBinding(DeviceFleetProfile.DEVICE_COMMAND, Transport.HTTP, Channel.path("/commands")),
                new RouteBinding(DeviceFleetProfile.COMMAND_RESULT, Transport.HTTP, Channel.path("/command-result")),
                new RouteBinding(DeviceFleetProfile.CONFIG_ACK, Transport.TCP, Channel.port(6001))));
    }

    @Test
    void validConfigPasses() {
        assertThat(ConfigValidator.validate(catalog, pool, List.of(validDevice()))).isEmpty();
    }

    @Test
    void inboundTcpPortMustBeInPool() {
        EdgeConfig device = new EdgeConfig("gateway-1", null, null, null, null, null, List.of(
                new RouteBinding(DeviceFleetProfile.CONFIG_ACK, Transport.TCP, Channel.port(7777))));
        List<String> errors = ConfigValidator.validate(catalog, pool, List.of(device));
        assertThat(errors).singleElement().asString()
                .contains("7777").contains("port pool");
    }

    @Test
    void inboundChannelCollisionAcrossDevicesIsRejected() {
        EdgeConfig a = new EdgeConfig("a", null, null, null, null, null, List.of(
                new RouteBinding(DeviceFleetProfile.CONFIG_ACK, Transport.TCP, Channel.port(6001))));
        EdgeConfig b = new EdgeConfig("b", null, null, null, null, null, List.of(
                new RouteBinding(DeviceFleetProfile.COMMAND_RESULT, Transport.TCP, Channel.port(6001))));
        List<String> errors = ConfigValidator.validate(catalog, pool, List.of(a, b));
        assertThat(errors).singleElement().asString().contains("collision");
    }

    @Test
    void sameChannelValueOnDifferentTransportsDoesNotCollide() {
        EdgeConfig device = new EdgeConfig("a", "http://e", null, null, null, null, List.of(
                new RouteBinding(DeviceFleetProfile.COMMAND_RESULT, Transport.HTTP, Channel.path("/confirm")),
                new RouteBinding(DeviceFleetProfile.REPORT_UPLOAD, Transport.FTP, Channel.folder("/confirm"))));
        assertThat(ConfigValidator.validate(catalog, pool, List.of(device))).isEmpty();
    }

    @Test
    void unknownMessageTypeIsRejected() {
        EdgeConfig device = new EdgeConfig("a", "http://e", null, null, null, null, List.of(
                new RouteBinding(MessageType.of("MYSTERY"), Transport.HTTP, Channel.path("/x"))));
        List<String> errors = ConfigValidator.validate(catalog, pool, List.of(device));
        assertThat(errors).singleElement().asString().contains("unknown message type MYSTERY");
    }

    @Test
    void transportAndChannelKindMustAgree() {
        EdgeConfig device = new EdgeConfig("a", "http://e", null, null, null, null, List.of(
                new RouteBinding(DeviceFleetProfile.DEVICE_COMMAND, Transport.TCP, Channel.path("/x"))));
        List<String> errors = ConfigValidator.validate(catalog, pool, List.of(device));
        assertThat(errors).singleElement().asString().contains("requires a PORT channel");
    }

    @Test
    void outboundBindingsRequireDeviceInfrastructure() {
        EdgeConfig noBaseUrl = new EdgeConfig("a", null, null, null, null, null, List.of(
                new RouteBinding(DeviceFleetProfile.DEVICE_COMMAND, Transport.HTTP, Channel.path("/x"))));
        assertThat(ConfigValidator.validate(catalog, pool, List.of(noBaseUrl)))
                .singleElement().asString().contains("baseUrl");

        EdgeConfig noHost = new EdgeConfig("b", null, null, null, null, null, List.of(
                new RouteBinding(DeviceFleetProfile.CONFIG_UPDATE, Transport.TCP, Channel.port(9001))));
        assertThat(ConfigValidator.validate(catalog, pool, List.of(noHost)))
                .singleElement().asString().contains("host");

        // outbound TCP ports are on the device, not the proxy: pool does not apply
        EdgeConfig outboundPortOutsidePool = new EdgeConfig("c", null, "10.0.0.9", null, null, null,
                List.of(new RouteBinding(DeviceFleetProfile.CONFIG_UPDATE, Transport.TCP, Channel.port(9001))));
        assertThat(ConfigValidator.validate(catalog, pool, List.of(outboundPortOutsidePool))).isEmpty();
    }

    @Test
    void duplicateDeviceIdsAreRejected() {
        List<String> errors = ConfigValidator.validate(catalog, pool,
                List.of(validDevice(), validDevice()));
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("duplicate deviceId"));
    }

    @Test
    void validMllpTcpProtocolPasses() {
        TcpProtocol mllp = new TcpProtocol("<VT>", "<FS><CR>",
                "<VT>ACK {activityId}<FS><CR>", "<VT>NAK {reason}<FS><CR>", "ACK", true);
        EdgeConfig device = new EdgeConfig("gateway-1", null, "10.0.0.5", null, null, null, List.of(
                new RouteBinding(DeviceFleetProfile.CONFIG_ACK, Transport.TCP,
                        Channel.port(6001))), mllp);
        assertThat(ConfigValidator.validate(catalog, pool, List.of(device))).isEmpty();
    }

    @Test
    void tcpProtocolOverrideRequiresTcpTransport() {
        TcpProtocol proto = new TcpProtocol(null, "<LF>", null, null, null, null);
        EdgeConfig device = new EdgeConfig("a", "http://e", null, null, null, null, List.of(
                new RouteBinding(DeviceFleetProfile.DEVICE_COMMAND, Transport.HTTP,
                        Channel.path("/x"), null, proto)));
        List<String> errors = ConfigValidator.validate(catalog, pool, List.of(device));
        assertThat(errors).singleElement().asString()
                .isEqualTo("a: tcpProtocol override requires TCP transport, got HTTP");
    }

    @Test
    void tcpProtocolFieldsMustParseAndBeNonEmpty() {
        TcpProtocol bad = new TcpProtocol("\\x0", "", null, null, null, null);
        EdgeConfig device = new EdgeConfig("a", null, "10.0.0.5", null, null, null,
                List.of(), bad);
        List<String> errors = ConfigValidator.validate(catalog, pool, List.of(device));
        assertThat(errors).containsExactly(
                "a: device tcpProtocol.startDelimiter: \\x escape requires two hex digits at position 0",
                "a: device tcpProtocol.endDelimiter must not be empty");
    }

    @Test
    void startDelimiterRequiresEndDelimiter() {
        TcpProtocol startOnly = new TcpProtocol("<STX>", null, null, null, null, null);
        EdgeConfig device = new EdgeConfig("a", null, "10.0.0.5", null, null, null,
                List.of(), startOnly);
        assertThat(ConfigValidator.validate(catalog, pool, List.of(device)))
                .containsExactly("a: device tcpProtocol: startDelimiter requires endDelimiter");

        // end-only IS legal: newline-terminated protocols
        TcpProtocol endOnly = new TcpProtocol(null, "<LF>", null, null, null, null);
        EdgeConfig ok = new EdgeConfig("b", null, "10.0.0.5", null, null, null,
                List.of(), endOnly);
        assertThat(ConfigValidator.validate(catalog, pool, List.of(ok))).isEmpty();
    }

    @Test
    void fireAndForgetWithExpectedAckIsContradictory() {
        TcpProtocol contradiction = new TcpProtocol(null, "<LF>", null, null, "PONG", false);
        EdgeConfig device = new EdgeConfig("a", null, "10.0.0.5", null, null, null,
                List.of(), contradiction);
        assertThat(ConfigValidator.validate(catalog, pool, List.of(device)))
                .containsExactly("a: device tcpProtocol: expectedAck is meaningless when awaitReply is false");
    }

    @Test
    void bindingLevelProtocolIsValidatedWithBindingLabel() {
        TcpProtocol bad = new TcpProtocol(null, "<NOPE>", null, null, null, null);
        EdgeConfig device = new EdgeConfig("a", null, "10.0.0.5", null, null, null, List.of(
                new RouteBinding(DeviceFleetProfile.CONFIG_ACK, Transport.TCP,
                        Channel.port(6001), null, bad)));
        assertThat(ConfigValidator.validate(catalog, pool, List.of(device)))
                .containsExactly("a: CONFIG_ACK tcpProtocol.endDelimiter: unknown token '<NOPE>' at position 0");
    }

    @Test
    void multiTypeResolverOnlyAllowedOnFtp() {
        EdgeConfig device = new EdgeConfig("a", "http://e", null, null, null, null, List.of(
                new RouteBinding(null, Transport.HTTP, Channel.path("/mixed"),
                        new ResolverConfig("filename-pattern", java.util.Map.of()))));
        List<String> errors = ConfigValidator.validate(catalog, pool, List.of(device));
        assertThat(errors).singleElement().asString().contains("only supported on FTP");
    }

    // ---- Persistent TCP session (TcpSession) rules. Mirrored in validate.test.ts. ----

    @Test
    void validPersistentClientSessionPasses() {
        TcpSession session = new TcpSession(TcpSession.Mode.PERSISTENT, TcpSession.Role.CLIENT,
                9001, null, null,
                new TcpSession.Heartbeat(30, "<VT>PING<FS>", "PONG", 5000, 60, 3), null);
        EdgeConfig device = new EdgeConfig("a", null, "10.0.0.5", null, null, null,
                List.of(), null, session);
        assertThat(ConfigValidator.validate(catalog, pool, List.of(device))).isEmpty();
    }

    @Test
    void validPersistentServerWatchdogSessionPasses() {
        // SERVER role, inbound watchdog only (no outbound ping), no device host needed
        TcpSession session = new TcpSession(TcpSession.Mode.PERSISTENT, TcpSession.Role.SERVER,
                null, 6005, null, new TcpSession.Heartbeat(null, null, null, null, 60, 2), null);
        EdgeConfig device = new EdgeConfig("a", null, null, null, null, null,
                List.of(), null, session);
        assertThat(ConfigValidator.validate(catalog, pool, List.of(device))).isEmpty();
    }

    @Test
    void perMessageSessionIsNotValidated() {
        // PER_MESSAGE short-circuits: no role / no liveness is ignored (that's today's behavior)
        TcpSession session = new TcpSession(TcpSession.Mode.PER_MESSAGE, null, null, null, null,
                null, null);
        EdgeConfig device = new EdgeConfig("a", null, null, null, null, null,
                List.of(), null, session);
        assertThat(ConfigValidator.validate(catalog, pool, List.of(device))).isEmpty();
    }

    @Test
    void persistentSessionRequiresRole() {
        TcpSession session = new TcpSession(TcpSession.Mode.PERSISTENT, null, null, null, null,
                new TcpSession.Heartbeat(30, "PING", null, null, null, 3), null);
        EdgeConfig device = new EdgeConfig("a", null, "10.0.0.5", null, null, null,
                List.of(), null, session);
        assertThat(ConfigValidator.validate(catalog, pool, List.of(device)))
                .containsExactly("a: tcpSession: PERSISTENT session requires a role (CLIENT or SERVER)");
    }

    @Test
    void clientSessionRequiresHostAndPort() {
        TcpSession session = new TcpSession(TcpSession.Mode.PERSISTENT, TcpSession.Role.CLIENT,
                null, null, null, new TcpSession.Heartbeat(30, "PING", null, null, null, 3), null);
        EdgeConfig device = new EdgeConfig("a", null, null, null, null, null,
                List.of(), null, session);
        assertThat(ConfigValidator.validate(catalog, pool, List.of(device))).containsExactly(
                "a: tcpSession: CLIENT role requires the device host",
                "a: tcpSession: CLIENT role requires a port");
    }

    @Test
    void serverSessionRequiresListenPort() {
        TcpSession session = new TcpSession(TcpSession.Mode.PERSISTENT, TcpSession.Role.SERVER,
                null, null, null, new TcpSession.Heartbeat(null, null, null, null, 60, 2), null);
        EdgeConfig device = new EdgeConfig("a", null, null, null, null, null,
                List.of(), null, session);
        assertThat(ConfigValidator.validate(catalog, pool, List.of(device)))
                .containsExactly("a: tcpSession: SERVER role requires a listenPort");
    }

    @Test
    void sharedServerPortRequiresHandshake() {
        assertThat(ConfigValidator.validate(catalog, pool, List.of(
                serverDevice("a", 6005, null), serverDevice("b", 6005, null)))).containsExactly(
                "a: tcpSession: SERVER listen port 6005 is shared, so a handshakeId is required",
                "b: tcpSession: SERVER listen port 6005 is shared, so a handshakeId is required");
    }

    @Test
    void sharedServerPortRequiresDistinctHandshakes() {
        assertThat(ConfigValidator.validate(catalog, pool, List.of(
                serverDevice("a", 6005, "dev"), serverDevice("b", 6005, "dev")))).containsExactly(
                "b: tcpSession: duplicate handshakeId 'dev' on shared SERVER listen port 6005");
    }

    @Test
    void sharedServerPortWithDistinctHandshakesPasses() {
        assertThat(ConfigValidator.validate(catalog, pool, List.of(
                serverDevice("a", 6005, "dev-a"), serverDevice("b", 6005, "dev-b")))).isEmpty();
    }

    private static EdgeConfig serverDevice(String id, int listenPort, String handshakeId) {
        TcpSession session = new TcpSession(TcpSession.Mode.PERSISTENT, TcpSession.Role.SERVER,
                null, listenPort, handshakeId, new TcpSession.Heartbeat(null, null, null, null, 60, 2), null);
        return new EdgeConfig(id, null, null, null, null, null, List.of(), null, session);
    }

    @Test
    void persistentSessionRequiresAtLeastOneLivenessMechanism() {
        TcpSession session = new TcpSession(TcpSession.Mode.PERSISTENT, TcpSession.Role.CLIENT,
                9001, null, null, null, null);
        EdgeConfig device = new EdgeConfig("a", null, "10.0.0.5", null, null, null,
                List.of(), null, session);
        assertThat(ConfigValidator.validate(catalog, pool, List.of(device))).containsExactly(
                "a: tcpSession: PERSISTENT session requires at least one liveness mechanism "
                        + "(heartbeat.sendIntervalSec or heartbeat.expectInboundSec)");
    }

    @Test
    void heartbeatWireFieldsMustParse() {
        TcpSession session = new TcpSession(TcpSession.Mode.PERSISTENT, TcpSession.Role.CLIENT,
                9001, null, null, new TcpSession.Heartbeat(30, "<NOPE>", null, null, null, 3), null);
        EdgeConfig device = new EdgeConfig("a", null, "10.0.0.5", null, null, null,
                List.of(), null, session);
        assertThat(ConfigValidator.validate(catalog, pool, List.of(device))).containsExactly(
                "a: tcpSession.heartbeat.sendPayload: unknown token '<NOPE>' at position 0");
    }

    @Test
    void outboundPingRequiresPayload() {
        TcpSession session = new TcpSession(TcpSession.Mode.PERSISTENT, TcpSession.Role.CLIENT,
                9001, null, null, new TcpSession.Heartbeat(30, null, null, null, null, 3), null);
        EdgeConfig device = new EdgeConfig("a", null, "10.0.0.5", null, null, null,
                List.of(), null, session);
        assertThat(ConfigValidator.validate(catalog, pool, List.of(device)))
                .containsExactly("a: tcpSession.heartbeat: sendIntervalSec requires sendPayload");
    }

    @Test
    void expectReplyRequiresOutboundPing() {
        // watchdog provides liveness; an expectReply with no ping to answer is meaningless
        TcpSession session = new TcpSession(TcpSession.Mode.PERSISTENT, TcpSession.Role.SERVER,
                null, 6005, null, new TcpSession.Heartbeat(null, null, "PONG", null, 60, 2), null);
        EdgeConfig device = new EdgeConfig("a", null, null, null, null, null,
                List.of(), null, session);
        assertThat(ConfigValidator.validate(catalog, pool, List.of(device)))
                .containsExactly("a: tcpSession.heartbeat: expectReply requires sendIntervalSec");
    }

    @Test
    void heartbeatIntervalsMustBePositive() {
        TcpSession session = new TcpSession(TcpSession.Mode.PERSISTENT, TcpSession.Role.CLIENT,
                9001, null, null, new TcpSession.Heartbeat(0, "PING", null, null, null, 3), null);
        EdgeConfig device = new EdgeConfig("a", null, "10.0.0.5", null, null, null,
                List.of(), null, session);
        assertThat(ConfigValidator.validate(catalog, pool, List.of(device)))
                .containsExactly("a: tcpSession.heartbeat.sendIntervalSec must be positive");
    }

    @Test
    void validInboundTypePasses() {
        TcpSession session = persistentClient("COMMAND_RESULT"); // an EDGE_TO_CLOUD type
        EdgeConfig device = new EdgeConfig("a", null, "10.0.0.5", null, null, null,
                List.of(), null, session);
        assertThat(ConfigValidator.validate(catalog, pool, List.of(device))).isEmpty();
    }

    @Test
    void unknownInboundTypeIsRejected() {
        TcpSession session = persistentClient("MYSTERY");
        EdgeConfig device = new EdgeConfig("a", null, "10.0.0.5", null, null, null,
                List.of(), null, session);
        assertThat(ConfigValidator.validate(catalog, pool, List.of(device)))
                .containsExactly("a: tcpSession: unknown inboundType 'MYSTERY'");
    }

    @Test
    void inboundTypeMustBeEdgeToCloud() {
        TcpSession session = persistentClient("DEVICE_COMMAND"); // a CLOUD_TO_EDGE type
        EdgeConfig device = new EdgeConfig("a", null, "10.0.0.5", null, null, null,
                List.of(), null, session);
        assertThat(ConfigValidator.validate(catalog, pool, List.of(device)))
                .containsExactly("a: tcpSession: inboundType 'DEVICE_COMMAND' must be an EDGE_TO_CLOUD type");
    }

    private static TcpSession persistentClient(String inboundType) {
        return new TcpSession(TcpSession.Mode.PERSISTENT, TcpSession.Role.CLIENT, 9001, null, null,
                new TcpSession.Heartbeat(30, "PING", null, null, null, 3), null, inboundType);
    }

    @Test
    void validResolverPasses() {
        EdgeConfig device = resolverClient(Map.of("STATUS", "COMMAND_RESULT")); // EDGE_TO_CLOUD type
        assertThat(ConfigValidator.validate(catalog, pool, List.of(device))).isEmpty();
    }

    @Test
    void inboundTypeAndResolverAreMutuallyExclusive() {
        // each individually valid -> only the mutual-exclusion error
        TcpSession session = new TcpSession(TcpSession.Mode.PERSISTENT, TcpSession.Role.CLIENT,
                9001, null, null, new TcpSession.Heartbeat(30, "PING", null, null, null, 3), null,
                "COMMAND_RESULT", new ResolverConfig("content-pattern", Map.of("S", "COMMAND_RESULT")));
        EdgeConfig device = new EdgeConfig("a", null, "10.0.0.5", null, null, null,
                List.of(), null, session);
        assertThat(ConfigValidator.validate(catalog, pool, List.of(device)))
                .containsExactly("a: tcpSession: set either inboundType or resolver, not both");
    }

    @Test
    void resolverKindMustNotBeBlank() {
        EdgeConfig device = resolverClient("", Map.of("STATUS", "COMMAND_RESULT"));
        assertThat(ConfigValidator.validate(catalog, pool, List.of(device)))
                .containsExactly("a: tcpSession: resolver kind must not be blank");
    }

    @Test
    void resolverMustMapToKnownEdgeToCloudTypes() {
        assertThat(ConfigValidator.validate(catalog, pool, List.of(resolverClient(Map.of("X", "MYSTERY")))))
                .containsExactly("a: tcpSession: resolver maps to unknown type 'MYSTERY'");
        assertThat(ConfigValidator.validate(catalog, pool, List.of(resolverClient(Map.of("X", "DEVICE_COMMAND")))))
                .containsExactly("a: tcpSession: resolver type 'DEVICE_COMMAND' must be an EDGE_TO_CLOUD type");
    }

    private static EdgeConfig resolverClient(Map<String, String> patterns) {
        return resolverClient("content-pattern", patterns);
    }

    private static EdgeConfig resolverClient(String kind, Map<String, String> patterns) {
        TcpSession session = new TcpSession(TcpSession.Mode.PERSISTENT, TcpSession.Role.CLIENT,
                9001, null, null, new TcpSession.Heartbeat(30, "PING", null, null, null, 3), null,
                null, new ResolverConfig(kind, patterns));
        return new EdgeConfig("a", null, "10.0.0.5", null, null, null, List.of(), null, session);
    }
}
