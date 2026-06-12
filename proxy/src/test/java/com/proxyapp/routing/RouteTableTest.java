package com.proxyapp.routing;

import com.proxyapp.profile.WarehouseProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteTableTest {

    private final MessageCatalog catalog = new WarehouseProfile().catalog();

    private EdgeConfig demoDevice() {
        return new EdgeConfig("mhe-1", "http://edge:8082", "10.0.0.5", 2222, "u", "p", List.of(
                new RouteBinding(WarehouseProfile.WAVE_RELEASE, Transport.HTTP, Channel.path("/pick-tasks")),
                new RouteBinding(WarehouseProfile.PICK_CONFIRM, Transport.HTTP, Channel.path("/pick-confirm")),
                new RouteBinding(WarehouseProfile.PUTAWAY_CONFIRM, Transport.TCP, Channel.port(6001)),
                new RouteBinding(WarehouseProfile.CYCLE_COUNT_CONFIRM, Transport.FTP, Channel.folder("cc-confirm"))));
    }

    @Test
    void resolvesOutboundByType() {
        RouteTable table = new RouteTable(catalog, List.of(demoDevice()));

        RouteTable.OutboundRoute route = table.resolveOutbound(WarehouseProfile.WAVE_RELEASE).orElseThrow();
        assertThat(route.device().deviceId()).isEqualTo("mhe-1");
        assertThat(route.binding().channel()).isEqualTo(Channel.path("/pick-tasks"));
        assertThat(route.entry().direction()).isEqualTo(Direction.CLOUD_TO_EDGE);
    }

    @Test
    void resolvesInboundByChannelNeverByPayload() {
        RouteTable table = new RouteTable(catalog, List.of(demoDevice()));

        assertThat(table.resolveInbound(Transport.HTTP, "/pick-confirm").orElseThrow()
                .entry().type()).isEqualTo(WarehouseProfile.PICK_CONFIRM);
        assertThat(table.resolveInbound(Transport.TCP, "6001").orElseThrow()
                .entry().type()).isEqualTo(WarehouseProfile.PUTAWAY_CONFIRM);
        assertThat(table.resolveInbound(Transport.FTP, "cc-confirm").orElseThrow()
                .entry().type()).isEqualTo(WarehouseProfile.CYCLE_COUNT_CONFIRM);
        // same channel value on a different transport is a different channel
        assertThat(table.resolveInbound(Transport.HTTP, "6001")).isEmpty();
        assertThat(table.resolveInbound(Transport.HTTP, "/unknown")).isEmpty();
    }

    @Test
    void exposesListenerChannelsPerTransport() {
        RouteTable table = new RouteTable(catalog, List.of(demoDevice()));

        assertThat(table.inboundTcpPorts()).isEqualTo(Set.of(6001));
        assertThat(table.inboundFtpFolders()).isEqualTo(Set.of("cc-confirm"));
        assertThat(table.inboundHttpPaths()).isEqualTo(Set.of("/pick-confirm"));
    }

    @Test
    void multiTypeBindingRoutesWithoutCatalogEntry() {
        EdgeConfig device = new EdgeConfig("mhe-2", null, "10.0.0.6", 2222, "u", "p", List.of(
                new RouteBinding(null, Transport.FTP, Channel.folder("mixed"),
                        new ResolverConfig("filename-pattern", java.util.Map.of(
                                "PC-.*\\.json", "PICK_CONFIRM")))));
        RouteTable table = new RouteTable(catalog, List.of(device));

        RouteTable.InboundRoute route = table.resolveInbound(Transport.FTP, "mixed").orElseThrow();
        assertThat(route.isMultiType()).isTrue();
        assertThat(route.entry()).isNull();
    }

    @Test
    void tcpProtocolPrecedenceIsBindingThenDeviceThenNull() {
        TcpProtocol devProto = new TcpProtocol("<VT>", "<FS><CR>", null, null, "ACK", null);
        TcpProtocol bindProto = new TcpProtocol(null, "<LF>", null, null, "PONG", null);
        EdgeConfig device = new EdgeConfig("mhe-1", null, "10.0.0.5", null, null, null, List.of(
                // inherits the device default
                new RouteBinding(WarehouseProfile.PUTAWAY_CONFIRM, Transport.TCP, Channel.port(6001)),
                // overrides it
                new RouteBinding(WarehouseProfile.CONTAINER_PUTAWAY, Transport.TCP,
                        Channel.port(9001), null, bindProto)),
                devProto);
        RouteTable table = new RouteTable(catalog, List.of(device));

        assertThat(table.resolveInbound(Transport.TCP, "6001").orElseThrow()
                .effectiveTcpProtocol()).isEqualTo(devProto);
        assertThat(table.resolveOutbound(WarehouseProfile.CONTAINER_PUTAWAY).orElseThrow()
                .effectiveTcpProtocol()).isEqualTo(bindProto);

        // a device with no protocol anywhere resolves to null (legacy)
        RouteTable legacy = new RouteTable(catalog, List.of(demoDevice()));
        assertThat(legacy.resolveInbound(Transport.TCP, "6001").orElseThrow()
                .effectiveTcpProtocol()).isNull();
    }

    @Test
    void inboundTcpProtocolsMapsPortToEffectiveProtocol() {
        TcpProtocol devProto = new TcpProtocol(null, "<LF>", "OK {activityId}", null, null, null);
        EdgeConfig device = new EdgeConfig("mhe-1", null, "10.0.0.5", null, null, null, List.of(
                new RouteBinding(WarehouseProfile.PUTAWAY_CONFIRM, Transport.TCP, Channel.port(6001))),
                devProto);
        RouteTable table = new RouteTable(catalog, List.of(device, new EdgeConfig(
                "legacy-dev", null, "10.0.0.6", null, null, null, List.of(
                        new RouteBinding(WarehouseProfile.PICK_CONFIRM, Transport.TCP, Channel.port(6002))))));

        assertThat(table.inboundTcpProtocols())
                .containsEntry(6001, devProto)
                .containsEntry(6002, null)
                .hasSize(2);
    }

    @Test
    void unknownTypeInBindingFails() {
        EdgeConfig device = new EdgeConfig("mhe-3", "http://e", null, null, null, null, List.of(
                new RouteBinding(MessageType.of("NOPE"), Transport.HTTP, Channel.path("/x"))));
        assertThatThrownBy(() -> new RouteTable(catalog, List.of(device)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOPE");
    }
}
