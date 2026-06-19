package com.proxyapp.connector;
import com.proxyapp.connector.model.ChannelTarget;

import com.proxyapp.routing.model.Transport;

/**
 * Outbound transport SPI. Sends run inside Temporal activities: they are retried
 * at-least-once, so every implementation must tolerate redelivery of the same payload.
 */
public interface Connector {

    Transport transport();

    void send(ChannelTarget target, byte[] payload);
}
