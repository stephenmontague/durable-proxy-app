package com.dummyedge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persistent-session device channel (the {@code persistent} Spring profile). The device listens on
 * a port; the proxy dials in as a CLIENT and keeps the socket warm. Over that one socket the device:
 * <ul>
 *   <li>answers {@code PING} with {@code PONG} (the proxy's heartbeat),</li>
 *   <li>acks a command frame with {@code ACK} (completing the proxy's correlated send), and</li>
 *   <li>either pushes a configured <b>telemetry payload</b> every {@code emitIntervalSec}
 *       ({@code edge.persistent.emit-payload} — verbatim, so CSV/XML/anything) or, when none is
 *       configured, pushes the paired CONFIG_ACK in response to a command.</li>
 * </ul>
 * Framing comes from {@code edge.tcp} (start/end delimiters); newline when unset. This one binary
 * therefore plays any device — a CSV/STX-ETX scanner or an XML/{@code <start>…end} meter — purely
 * from config. Disabled (no-op) outside the persistent profile.
 */
@Component
public class PersistentDeviceServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PersistentDeviceServer.class);
    /** Heartbeat/ack trace — its own logger so `logging.level.heartbeat` toggles it independently. */
    private static final Logger hb = LoggerFactory.getLogger("heartbeat");

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

    /** One persistent connection: framed (or newline) frames until the proxy drops the socket. */
    private void handle(Socket socket) {
        log.info("proxy connected to the persistent device channel");
        EdgeProperties.Tcp tcp = properties.tcp();
        byte[] start = tcp != null && tcp.framed() ? bytes(tcp.startDelimiter()) : null;
        byte[] end = tcp != null && tcp.framed()
                ? bytes(tcp.endDelimiter()) : "\n".getBytes(StandardCharsets.ISO_8859_1);
        Object writeLock = new Object();
        try (socket) {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            if (properties.persistent().emits()) {
                executor.execute(() -> emitLoop(socket, out, writeLock, start, end));
            }
            byte[] frame;
            while ((frame = readFrame(in, start, end)) != null) {
                String content = new String(frame, StandardCharsets.ISO_8859_1).trim();
                if (content.isEmpty()) {
                    continue;
                }
                if ("PING".equals(content)) {
                    writeFramed(out, writeLock, "PONG", start, end);
                    hb.info("<- PING   -> PONG");
                } else {
                    onCommand(out, writeLock, content, start, end);
                }
            }
        } catch (IOException e) {
            log.info("persistent device connection ended: {}", e.getMessage());
        }
    }

    private void onCommand(OutputStream out, Object writeLock, String content, byte[] start, byte[] end)
            throws IOException {
        log.info("<- command: {}", content);
        receivedStore.add("SESSION", String.valueOf(properties.persistent().listenPortOrDefault()), content);
        writeFramed(out, writeLock, "ACK", start, end);
        hb.info("-> ACK"); // completes the proxy's correlated send

        // Telemetry-emitting devices push on their own cadence (emitLoop); a device with no emit
        // configured answers a command with the paired CONFIG_ACK, like the original demo.
        if (!properties.persistent().emits()) {
            try {
                JsonNode body = mapper.readTree(content);
                ObjectNode confirm = mapper.createObjectNode();
                confirm.set("configId", body.get("configId"));
                confirm.put("status", "APPLIED");
                writeFramed(out, writeLock, confirm.toString(), start, end);
            } catch (IOException e) {
                log.warn("could not build the session CONFIG_ACK for '{}': {}", content, e.getMessage());
            }
        }
    }

    /** Push the configured telemetry frame every interval until the socket closes. */
    private void emitLoop(Socket socket, OutputStream out, Object writeLock, byte[] start, byte[] end) {
        String payload = properties.persistent().emitPayload();
        long intervalMs = properties.persistent().emitIntervalSecOrDefault() * 1000L;
        while (!socket.isClosed()) {
            try {
                Thread.sleep(intervalMs);
                writeFramed(out, writeLock, payload, start, end);
                log.info("device emitted telemetry: {}", payload);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                return; // socket gone
            }
        }
    }

    private static void writeFramed(OutputStream out, Object writeLock, String content,
                                    byte[] start, byte[] end) throws IOException {
        synchronized (writeLock) {
            if (start != null) {
                out.write(start);
            }
            out.write(content.getBytes(StandardCharsets.ISO_8859_1));
            out.write(end);
            out.flush();
        }
    }

    /** Read one frame, stripping a leading start delimiter; the trailing end delimiter is removed. */
    private static byte[] readFrame(InputStream in, byte[] start, byte[] end) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        boolean seekingStart = start != null;
        int b;
        while ((b = in.read()) >= 0) {
            buf.write(b);
            byte[] arr = buf.toByteArray(); // demo frames are tiny
            if (seekingStart) {
                if (endsWith(arr, start)) {
                    buf.reset();
                    seekingStart = false;
                }
                continue;
            }
            if (endsWith(arr, end)) {
                return Arrays.copyOf(arr, arr.length - end.length);
            }
        }
        return null;
    }

    private static boolean endsWith(byte[] data, byte[] suffix) {
        if (data.length < suffix.length) {
            return false;
        }
        for (int i = 0; i < suffix.length; i++) {
            if (data[data.length - suffix.length + i] != suffix[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
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
