package com.proxyapp.connector;

import com.proxyapp.routing.model.Transport;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Connectors by transport. */
public final class ConnectorFactory {

    private final Map<Transport, Connector> connectors;

    public ConnectorFactory(List<Connector> connectors) {
        this.connectors = connectors.stream()
                .collect(Collectors.toMap(Connector::transport, Function.identity()));
    }

    public Connector require(Transport transport) {
        Connector connector = connectors.get(transport);
        if (connector == null) {
            throw new IllegalArgumentException("no connector for transport " + transport);
        }
        return connector;
    }
}
