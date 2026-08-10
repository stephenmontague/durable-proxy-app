package com.proxyapp.connector.model;

import com.proxyapp.routing.model.TcpProtocol;

/** Where an outbound send goes, expressed per transport. */
public sealed interface ChannelTarget {

    record HttpTarget(String url) implements ChannelTarget {
    }

    /** Null {@code protocol} = legacy framing. */
    record TcpTarget(String host, int port, TcpProtocol protocol) implements ChannelTarget {
    }

    /** {@code filename} is the activity id, so a retry overwrites the same remote file
     *  instead of duplicating it. */
    record FtpTarget(String host, int port, String user, String password,
                     String folder, String filename) implements ChannelTarget {
    }
}
