package com.proxyapp.routing;

import com.proxyapp.profile.DeviceFleetProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
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
}
