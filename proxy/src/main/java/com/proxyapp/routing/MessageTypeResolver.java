package com.proxyapp.routing;
import com.proxyapp.routing.model.MessageType;
import com.proxyapp.routing.model.ResolverConfig;
import com.proxyapp.routing.model.Transport;

import java.util.Optional;

/**
 * Opt-in SPI for opaque-multiplexed devices whose single channel carries multiple message
 * types. Off by default — channel-based routing needs no payload inspection.
 */
public interface MessageTypeResolver {

    /** Context available to a resolver for one inbound delivery. */
    record InboundContext(Transport transport, String channelValue, String filename, byte[] raw) {
    }

    /** Resolver implementation key matched against {@link ResolverConfig#kind()}. */
    String kind();

    Optional<MessageType> resolve(ResolverConfig config, InboundContext context);
}
