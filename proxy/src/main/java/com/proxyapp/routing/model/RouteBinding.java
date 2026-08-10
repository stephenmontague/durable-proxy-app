package com.proxyapp.routing.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Binds one message type to a transport + channel on an edge device. Direction comes from the
 * MessageCatalog, not the binding. A set {@code resolver} makes this a multi-type channel, where
 * {@code messageType} may be null and typing is delegated to the resolver (FTP folders only).
 * {@code tcpProtocol} overrides the device-level wire protocol; null inherits it.
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
