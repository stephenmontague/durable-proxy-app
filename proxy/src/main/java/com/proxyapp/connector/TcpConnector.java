package com.proxyapp.connector;

import com.proxyapp.connector.model.ChannelTarget;
import com.proxyapp.routing.WireString;
import com.proxyapp.routing.model.TcpProtocol;
import com.proxyapp.routing.model.Transport;
import com.proxyapp.wire.Bytes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Raw TCP delivery, shaped by the route's {@link TcpProtocol}. Legacy (null) writes the payload,
 * half-closes, and requires a reply starting with {@code ACK}. Configured wraps the payload in the
 * delimiters and succeeds the moment the reply contains {@code expectedAck} — framed devices never send
 * EOF, so waiting for stream end would time out. {@code awaitReply} false is fire-and-forget, weakening
 * the guarantee to "the write was accepted". A bad ack fails the send so the activity retries; raw TCP
 * has no store-and-forward of its own.
 */
public class TcpConnector implements Connector {

    private static final int REPLY_CAP_BYTES = 64 * 1024;

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public TcpConnector() {
        this(5_000, 15_000);
    }

    TcpConnector(int connectTimeoutMs, int readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public Transport transport() {
        return Transport.TCP;
    }

    @Override
    public void send(ChannelTarget target, byte[] payload) {
        ChannelTarget.TcpTarget tcp = (ChannelTarget.TcpTarget) target;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(tcp.host(), tcp.port()), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            if (tcp.protocol() == null) {
                sendLegacy(socket, tcp, payload);
            } else {
                sendConfigured(socket, tcp, payload, tcp.protocol());
            }
        } catch (IOException e) {
            throw new ConnectorSendException("TCP send to " + tcp.host() + ":" + tcp.port() + " failed", e);
        }
    }

    private void sendLegacy(Socket socket, ChannelTarget.TcpTarget tcp, byte[] payload)
            throws IOException {
        socket.getOutputStream().write(payload);
        socket.getOutputStream().flush();
        socket.shutdownOutput();
        String ack = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!ack.startsWith("ACK")) {
            throw new ConnectorSendException("TCP " + tcp.host() + ":" + tcp.port()
                    + " did not ack (got '" + ack.trim() + "')");
        }
    }

    private void sendConfigured(Socket socket, ChannelTarget.TcpTarget tcp, byte[] payload,
                                TcpProtocol protocol) throws IOException {
        OutputStream out = socket.getOutputStream();
        if (protocol.startDelimiter() != null) {
            out.write(WireString.decode(protocol.startDelimiter()));
        }
        out.write(payload);
        if (protocol.endDelimiter() != null) {
            out.write(WireString.decode(protocol.endDelimiter()));
        }
        out.flush();

        if (!protocol.shouldAwaitReply()) {
            socket.shutdownOutput(); // clean FIN; nothing to read
            return;
        }
        if (protocol.endDelimiter() == null) {
            // EOF-framed device with a custom ack word: it needs our FIN to see message end.
            socket.shutdownOutput();
        }

        byte[] expected = protocol.expectedAck() != null
                ? WireString.decode(protocol.expectedAck())
                : "ACK".getBytes(StandardCharsets.UTF_8);
        awaitAck(socket.getInputStream(), expected, tcp);
    }

    /** Incremental read with early exit; framed devices keep the socket open, so EOF may never come. */
    private void awaitAck(InputStream in, byte[] expected, ChannelTarget.TcpTarget tcp)
            throws IOException {
        byte[] acc = new byte[Math.max(256, expected.length * 4)];
        int size = 0;
        while (true) {
            int b;
            try {
                b = in.read();
            } catch (SocketTimeoutException e) {
                throw new ConnectorSendException("TCP " + tcp.host() + ":" + tcp.port()
                        + " sent no expected ack within " + readTimeoutMs + "ms (got '"
                        + printable(acc, size) + "')");
            }
            if (b < 0) {
                throw new ConnectorSendException("TCP " + tcp.host() + ":" + tcp.port()
                        + " closed without the expected ack (got '" + printable(acc, size) + "')");
            }
            if (size == acc.length) {
                if (size >= REPLY_CAP_BYTES) {
                    throw new ConnectorSendException("TCP " + tcp.host() + ":" + tcp.port()
                            + " reply exceeded " + REPLY_CAP_BYTES + " bytes without the expected ack");
                }
                acc = java.util.Arrays.copyOf(acc, Math.min(acc.length * 2, REPLY_CAP_BYTES));
            }
            acc[size++] = (byte) b;
            // endsWith, not contains: re-checked after every single-byte append, so only the tail can
            // have newly matched. A full scan per byte would make this read quadratic.
            if (Bytes.endsWith(acc, size, expected)) {
                return;
            }
        }
    }

    /** Render reply bytes safely for error messages: control bytes as \xNN. */
    private static String printable(byte[] data, int size) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(size, 120); i++) {
            int c = data[i] & 0xFF;
            if (c >= 0x20 && c <= 0x7E) {
                sb.append((char) c);
            } else {
                sb.append(String.format("\\x%02X", c));
            }
        }
        if (size > 120) {
            sb.append('…');
        }
        return sb.toString();
    }
}
