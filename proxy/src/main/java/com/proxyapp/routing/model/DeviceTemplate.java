package com.proxyapp.routing.model;

import java.util.List;

/**
 * Per-edge-model profile that pre-fills the typical message types and channel layout.
 * A configurator clones it and supplies only the site-specific values (baseUrl/host + base
 * ports) — in the common case nothing is typed by hand.
 */
public record DeviceTemplate(String id, String name, List<TemplateBinding> bindings) {

    /**
     * One pre-filled binding. For PORT channels the concrete port is {@code basePort + portOffset};
     * PATH/FOLDER channels use the literal {@code value}.
     */
    public record TemplateBinding(MessageType messageType, Transport transport, ChannelKind kind,
                                  String value, int portOffset) {
    }

    /** Site-specific values supplied when cloning the template. */
    public record SiteValues(String deviceId, String baseUrl, String host, int basePort,
                             Integer ftpPort, String ftpUser, String ftpPassword) {
    }

    public EdgeConfig materialize(SiteValues site) {
        List<RouteBinding> routes = bindings.stream()
                .map(b -> new RouteBinding(b.messageType(), b.transport(), switch (b.kind()) {
                    case PORT -> Channel.port(site.basePort() + b.portOffset());
                    case PATH -> Channel.path(b.value());
                    case FOLDER -> Channel.folder(b.value());
                }))
                .toList();
        return new EdgeConfig(site.deviceId(), site.baseUrl(), site.host(), site.ftpPort(),
                site.ftpUser(), site.ftpPassword(), routes);
    }
}
