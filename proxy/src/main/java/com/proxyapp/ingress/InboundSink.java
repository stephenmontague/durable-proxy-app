package com.proxyapp.ingress;

import com.proxyapp.routing.model.Transport;

/**
 * What a transport listener needs from the ingress pipeline: hand over one raw message,
 * get the enqueue result (or an {@link IngressException}). In production this is
 * {@link InboundGateway#handle}; tests can stub it without the gateway's dependencies.
 */
@FunctionalInterface
public interface InboundSink {

    InboundGateway.EnqueueResult accept(Transport transport, String channelValue,
                                        String filename, byte[] raw);
}
