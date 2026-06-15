package com.proxyapp.session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Minimal in-test TCP server: binds an ephemeral port, accepts connections, and runs a
 * per-connection handler on its own thread so the accept loop keeps going. Used to exercise
 * {@link DeviceSession}/{@link TcpSessionManager} CLIENT behavior without a real device.
 */
final class StubTcpServer implements AutoCloseable {

    private final ServerSocket server;
    private final Consumer<Socket> handler;
    private final boolean acceptOnce;
    final List<Socket> accepted = new CopyOnWriteArrayList<>();

    StubTcpServer(Consumer<Socket> handler) throws IOException {
        this(handler, false);
    }

    /** @param acceptOnce stop accepting after the first connection (so reconnects are refused). */
    StubTcpServer(Consumer<Socket> handler, boolean acceptOnce) throws IOException {
        this.server = new ServerSocket(0);
        this.handler = handler;
        this.acceptOnce = acceptOnce;
        Thread t = new Thread(this::acceptLoop, "stub-tcp-accept");
        t.setDaemon(true);
        t.start();
    }

    int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                return;
            }
            accepted.add(socket);
            Thread t = new Thread(() -> handler.accept(socket), "stub-tcp-conn");
            t.setDaemon(true);
            t.start();
            if (acceptOnce) {
                closeServerSocket();
                return;
            }
        }
    }

    /** Stop accepting new connections without disturbing ones already accepted. */
    void closeServerSocket() {
        try {
            server.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    @Override
    public void close() {
        closeServerSocket();
        for (Socket s : accepted) {
            try {
                s.close();
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    // ---- handlers ----

    /** Read and discard until the peer closes; keeps the connection alive but stays silent. */
    static void silent(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            while (in.read() >= 0) {
                // discard
            }
        } catch (IOException ignored) {
            // connection ended
        }
    }

    /** Reply {@code PONG\n} to every newline-terminated frame (answers the heartbeat ping). */
    static void pong(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            int b;
            while ((b = in.read()) >= 0) {
                if (b == '\n') {
                    out.write("PONG\n".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                }
            }
        } catch (IOException ignored) {
            // connection ended
        }
    }
}
