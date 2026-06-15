package com.dummyedge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persistent-session device channel (the {@code persistent} Spring profile). The device listens on
 * a port; the proxy dials in as a CLIENT and keeps the socket warm. Over that one newline-framed
 * socket the device:
 * <ul>
 *   <li>answers {@code PING} with {@code PONG} (the proxy's heartbeat),</li>
 *   <li>acks a command frame with {@code ACK} (completing the proxy's correlated send), and</li>
 *   <li>pushes the paired CONFIG_ACK back <b>over the same socket</b> — the proxy types it via the
 *       session's {@code inboundType} and starts a DeliverToCloud activity.</li>
 * </ul>
 * Pair with {@code config/persistent-routes.json}. Disabled (no-op) outside the persistent profile.
 */
@Component
public class PersistentDeviceServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PersistentDeviceServer.class);

    private final EdgeProperties properties;
    private final ReceivedStore receivedStore;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "edge-session");
        t.setDaemon(true);
        return t;
    });
    private volatile ServerSocket serverSocket;

    public PersistentDeviceServer(EdgeProperties properties, ReceivedStore receivedStore) {
        this.properties = properties;
        this.receivedStore = receivedStore;
    }

    @Override
    public void start() {
        if (!properties.persistentEnabled()) {
            log.info("persistent-session device channel disabled (enable with the 'persistent' profile)");
            return;
        }
        int port = properties.persistent().listenPortOrDefault();
        try {
            serverSocket = new ServerSocket(port);
        } catch (IOException e) {
            throw new IllegalStateException("cannot open persistent device port " + port, e);
        }
        executor.execute(this::acceptLoop);
        log.info("persistent device channel listening on port {} (proxy dials in, PING/PONG heartbeats)", port);
    }

    private void acceptLoop() {
        while (serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                executor.execute(() -> handle(socket));
            } catch (IOException e) {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    log.warn("persistent device accept failed: {}", e.getMessage());
                }
                return;
            }
        }
    }

    /** One persistent connection: many newline-framed frames until the proxy drops the socket. */
    private void handle(Socket socket) {
        log.info("proxy connected to the persistent device channel");
        try (socket) {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();
            String frame;
            while ((frame = in.readLine()) != null) {
                if (frame.isBlank()) {
                    continue;
                }
                if ("PING".equals(frame.trim())) {
                    write(out, "PONG\n"); // heartbeat reply
                } else {
                    onCommand(out, frame);
                }
            }
        } catch (IOException e) {
            log.info("persistent device connection ended: {}", e.getMessage());
        }
    }

    private void onCommand(OutputStream out, String frame) throws IOException {
        log.info("device received over persistent socket: {}", frame);
        receivedStore.add("SESSION", String.valueOf(properties.persistent().listenPortOrDefault()), frame);
        write(out, "ACK\n"); // completes the proxy's correlated send

        // Push the paired CONFIG_ACK back over the same socket; the proxy delivers it to the cloud.
        try {
            JsonNode body = mapper.readTree(frame);
            ObjectNode confirm = mapper.createObjectNode();
            confirm.set("configId", body.get("configId"));
            confirm.put("status", "APPLIED");
            write(out, confirm + "\n");
        } catch (IOException e) {
            log.warn("could not build the session CONFIG_ACK for '{}': {}", frame, e.getMessage());
        }
    }

    private static void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @Override
    public void stop() {
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // closing is best-effort
        }
        executor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return serverSocket != null && !serverSocket.isClosed();
    }
}
