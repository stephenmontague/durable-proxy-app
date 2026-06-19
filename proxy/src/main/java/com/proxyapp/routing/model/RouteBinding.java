package com.proxyapp.routing.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Binds one message type to a transport + channel on a specific edge device.
 * Direction comes from the {@link MessageCatalog}, not from the binding.
 *
 * <p>If {@code resolver} is set, this binding is a multi-type channel: {@code messageType}
 * may be null and inbound type resolution is delegated to the configured
 * {@link MessageTypeResolver} (opt-in, FTP folders only).
 *
 * <p>{@code tcpProtocol} (TCP bindings only) overrides the device-level wire protocol;
 * null inherits the device's, which itself defaults to legacy framing.
 */
public record RouteBinding(MessageType messageType, Transport transport, Channel channel,
                           ResolverConfig resolver, TcpProtocol tcpProtocol) {

    public RouteBinding(MessageType messageType, Transport transport, Channel channel) {
        this(messageType, transport, channel, null, null);
    }

    public RouteBinding(MessageType messageType, Transport transport, Channel channel,
                        ResolverConfig resolver) {
        this(messageType, transport, channel, resolver, null);
    }

    @JsonIgnore
    public boolean isMultiType() {
        return resolver != null;
    }
}
