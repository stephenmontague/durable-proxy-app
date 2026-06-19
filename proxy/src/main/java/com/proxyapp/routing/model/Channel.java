package com.proxyapp.routing.model;

/**
 * A transport-specific channel address: a port, an HTTP path, or an FTP folder.
 * The channel — never the payload — identifies the message type of inbound traffic.
 */
public record Channel(ChannelKind kind, String value) {

    public static Channel port(int port) {
        return new Channel(ChannelKind.PORT, Integer.toString(port));
    }

    public static Channel path(String path) {
        return new Channel(ChannelKind.PATH, path);
    }

    public static Channel folder(String folder) {
        return new Channel(ChannelKind.FOLDER, folder);
    }

    public int portValue() {
        return Integer.parseInt(value);
    }

    @Override
    public String toString() {
        return kind + ":" + value;
    }
}
