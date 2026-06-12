package com.proxyapp.connector;

import com.proxyapp.routing.TcpProtocol;

/** Where an outbound send goes, expressed per transport. */
public sealed interface ChannelTarget {

    record HttpTarget(String url) implements ChannelTarget {
    }

    /** @param protocol effective wire protocol for this route; null = legacy framing */
    record TcpTarget(String host, int port, TcpProtocol protocol) implements ChannelTarget {

        public TcpTarget(String host, int port) {
            this(host, port, null);
        }
    }

    /**
     * @param filename deterministic per-delivery name (the activity id) so an activity
     *                 retry overwrites the same remote file instead of duplicating it
     */
    record FtpTarget(String host, int port, String user, String password,
                     String folder, String filename) implements ChannelTarget {
    }
}
