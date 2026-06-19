package com.proxyapp.routing;
import com.proxyapp.routing.model.CatalogEntry;
import com.proxyapp.routing.model.Channel;
import com.proxyapp.routing.model.Direction;
import com.proxyapp.routing.model.EdgeConfig;
import com.proxyapp.routing.model.MessageType;
import com.proxyapp.routing.model.RouteBinding;
import com.proxyapp.routing.model.TcpProtocol;
import com.proxyapp.routing.model.Transport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable resolution of the current routing config, rebuilt by the Reconciler on every
 * config change. Resolves:
 * <ul>
 *   <li>outbound: (type) -> device + binding to send on</li>
 *   <li>inbound: (transport, channel) -> message type + binding (channel-based, zero payload inspection)</li>
 * </ul>
 */
public final class RouteTable {

    /** Outbound resolution: where to send one message type. */
    public record OutboundRoute(EdgeConfig device, RouteBinding binding, CatalogEntry entry) {
        /** Binding override wins over the device default; null = legacy wire behavior. */
        public TcpProtocol effectiveTcpProtocol() {
            return binding.tcpProtocol() != null ? binding.tcpProtocol() : device.tcpProtocol();
        }
    }

    /** Inbound resolution: what one channel carries and for which device. */
    public record InboundRoute(EdgeConfig device, RouteBinding binding, CatalogEntry entry) {
        public boolean isMultiType() {
            return binding.isMultiType();
        }

        /** Binding override wins over the device default; null = legacy wire behavior. */
        public TcpProtocol effectiveTcpProtocol() {
            return binding.tcpProtocol() != null ? binding.tcpProtocol() : device.tcpProtocol();
        }
    }

    private final MessageCatalog catalog;
    private final Map<MessageType, OutboundRoute> outbound = new LinkedHashMap<>();
    private final Map<String, InboundRoute> inbound = new LinkedHashMap<>();

    public RouteTable(MessageCatalog catalog, List<EdgeConfig> devices) {
        this.catalog = catalog;
        for (EdgeConfig device : devices == null ? List.<EdgeConfig>of() : devices) {
            for (RouteBinding binding : device.bindings()) {
                if (binding.isMultiType()) {
                    inbound.put(channelKey(binding.transport(), binding.channel().value()),
                            new InboundRoute(device, binding, null));
                    continue;
                }
                CatalogEntry entry = catalog.require(binding.messageType());
                if (entry.direction() == Direction.CLOUD_TO_EDGE) {
                    outbound.put(binding.messageType(), new OutboundRoute(device, binding, entry));
                } else {
                    inbound.put(channelKey(binding.transport(), binding.channel().value()),
                            new InboundRoute(device, binding, entry));
                }
            }
        }
    }

    public static RouteTable empty(MessageCatalog catalog) {
        return new RouteTable(catalog, List.of());
    }

    public MessageCatalog catalog() {
        return catalog;
    }

    public Optional<OutboundRoute> resolveOutbound(MessageType type) {
        return Optional.ofNullable(outbound.get(type));
    }

    public Optional<InboundRoute> resolveInbound(Transport transport, String channelValue) {
        return Optional.ofNullable(inbound.get(channelKey(transport, channelValue)));
    }

    /** All TCP ports the proxy must listen on. */
    public Set<Integer> inboundTcpPorts() {
        return inboundChannels(Transport.TCP).stream()
                .map(Integer::parseInt)
                .collect(Collectors.toSet());
    }

    /**
     * Inbound TCP port -> effective wire protocol for the listener on that port.
     * Values may be null (legacy protocol) — hence a HashMap, not Map.copyOf.
     * Channel-collision validation guarantees at most one binding per port.
     */
    public Map<Integer, TcpProtocol> inboundTcpProtocols() {
        Map<Integer, TcpProtocol> result = new LinkedHashMap<>();
        String prefix = Transport.TCP.name() + "|";
        for (Map.Entry<String, InboundRoute> e : inbound.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                result.put(Integer.parseInt(e.getKey().substring(prefix.length())),
                        e.getValue().effectiveTcpProtocol());
            }
        }
        return result;
    }

    /** All FTP folders the proxy must watch. */
    public Set<String> inboundFtpFolders() {
        return inboundChannels(Transport.FTP);
    }

    /** All HTTP paths the proxy accepts. */
    public Set<String> inboundHttpPaths() {
        return inboundChannels(Transport.HTTP);
    }

    private Set<String> inboundChannels(Transport transport) {
        String prefix = transport.name() + "|";
        return inbound.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .map(k -> k.substring(prefix.length()))
                .collect(Collectors.toSet());
    }

    private static String channelKey(Transport transport, String channelValue) {
        return transport.name() + "|" + channelValue;
    }
}
