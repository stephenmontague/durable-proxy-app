package com.dummyedge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TCP channel of the device: receives CONFIG_UPDATE on its listen port, acks, and
 * auto-pushes the paired CONFIG_ACK to the proxy's TCP ingress port.
 */
@Component
public class TcpDeviceServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TcpDeviceServer.class);

    private final EdgeProperties properties;
    private final ReceivedStore receivedStore;
    private final ConfirmPusher confirmPusher;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "edge-tcp");
        t.setDaemon(true);
        return t;
    });
    private ServerSocket serverSocket;

    public TcpDeviceServer(EdgeProperties properties, ReceivedStore receivedStore,
                           ConfirmPusher confirmPusher) {
        this.properties = properties;
        this.receivedStore = receivedStore;
        this.confirmPusher = confirmPusher;
    }

    @Override
    public void start() {
        try {
            serverSocket = new ServerSocket(properties.tcpListenPort());
        } catch (IOException e) {
            throw new IllegalStateException("cannot open device TCP port " + properties.tcpListenPort(), e);
        }
        executor.execute(this::acceptLoop);
        log.info("device TCP channel listening on port {}", properties.tcpListenPort());
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                executor.execute(() -> handle(socket));
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    log.warn("device TCP accept failed: {}", e.getMessage());
                }
                return;
            }
        }
    }

    private void handle(Socket socket) {
        EdgeProperties.Tcp tcp = properties.tcp();
        boolean framed = tcp != null && tcp.framed();
        try (socket) {
            socket.setSoTimeout(10_000);
            if (!framed) {
                String payload = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                process(socket, payload, "ACK\n".getBytes(StandardCharsets.UTF_8));
                return;
            }
            // Framed mode: the proxy doesn't half-close, so read frames by delimiter.
            byte[] start = delimiterBytes(tcp.startDelimiter());
            byte[] end = delimiterBytes(tcp.endDelimiter());
            byte[] ack = tcp.ackReply() != null && !tcp.ackReply().isEmpty()
                    ? tcp.ackReply().getBytes(StandardCharsets.ISO_8859_1)
                    : "ACK\n".getBytes(StandardCharsets.UTF_8);
            java.io.InputStream in = new java.io.BufferedInputStream(socket.getInputStream());
            byte[] frame;
            while ((frame = readFrame(in, start, end)) != null) {
                process(socket, new String(frame, StandardCharsets.UTF_8), ack);
            }
        } catch (IOException e) {
            log.warn("device TCP connection failed: {}", e.getMessage());
        }
    }

    private void process(Socket socket, String payload, byte[] ack) throws IOException {
        log.info("device received config update over TCP: {}", payload.trim());
        receivedStore.add("TCP", String.valueOf(properties.tcpListenPort()), payload);

        socket.getOutputStream().write(ack);
        socket.getOutputStream().flush();

        JsonNode body = mapper.readTree(payload);
        ObjectNode confirm = mapper.createObjectNode();
        confirm.set("configId", body.get("configId"));
        confirm.put("status", "APPLIED");
        confirmPusher.pushTcpConfigAck(confirm.toString());
    }

    private static byte[] delimiterBytes(String s) {
        return s == null || s.isEmpty() ? null : s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Read one delimited frame (start stripped if configured); null on EOF. */
    private static byte[] readFrame(java.io.InputStream in, byte[] start, byte[] end)
            throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
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
                return java.util.Arrays.copyOf(arr, arr.length - end.length);
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
