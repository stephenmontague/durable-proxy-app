package com.proxyapp.session;
import com.proxyapp.control.model.AppliedStatus;
import com.proxyapp.session.model.DeviceSessionConfig;
import com.proxyapp.session.model.DeviceSessionStatus;

import com.proxyapp.routing.model.TcpSession;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The proxy's <b>connection table</b> for persistent device links: a {@code Map<deviceId,
 * DeviceSession>}. Mirrors {@link com.proxyapp.ingress.TcpSocketServer} /
 * {@code FtpIngressListener} — the {@code Reconciler} calls {@link #reconcile} as control state
 * changes, opening new sessions, closing removed ones, and hot-replacing changed ones. Sessions
 * live outside Temporal; the outbound activity borrows them as a transport (phase 3).
 */
public class TcpSessionManager {

    private static final Logger log = LoggerFactory.getLogger(TcpSessionManager.class);
    private static final int ACCEPT_IDLE_MS = 1_000;     // accept() wakeup so close() is noticed
    private static final int HANDSHAKE_TIMEOUT_MS = 5_000; // wait for a shared-port device's id line

    private final BiConsumer<DeviceSessionConfig, byte[]> inboundSink;
    private final int connectTimeoutMs;
    private final long minBackoffMs;
    private final long maxBackoffMs;
    private final long sendAckTimeoutMs;

    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();
    private final Map<Integer, ServerSocket> acceptors = new ConcurrentHashMap<>(); // SERVER listen ports
    private final AtomicInteger threadSeq = new AtomicInteger();
    private final ExecutorService connectExecutor = Executors.newCachedThreadPool(
            r -> daemon(r, "tcp-session-conn-" + threadSeq.incrementAndGet()));
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            2, r -> daemon(r, "tcp-session-hb-" + threadSeq.incrementAndGet()));

    public TcpSessionManager(BiConsumer<DeviceSessionConfig, byte[]> inboundSink) {
        // Dial timeout 5s; reconnect backoff 1s..30s (doubling); send-ack wait 15s.
        this(inboundSink, 5_000, 1_000, 30_000, 15_000);
    }

    /** Test convenience: no-op inbound sink, default send-ack timeout. */
    TcpSessionManager(int connectTimeoutMs, long minBackoffMs, long maxBackoffMs) {
        this((cfg, frame) -> { }, connectTimeoutMs, minBackoffMs, maxBackoffMs, 15_000);
    }

    TcpSessionManager(BiConsumer<DeviceSessionConfig, byte[]> inboundSink, int connectTimeoutMs,
                      long minBackoffMs, long maxBackoffMs, long sendAckTimeoutMs) {
        this.inboundSink = inboundSink;
        this.connectTimeoutMs = connectTimeoutMs;
        this.minBackoffMs = minBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
        this.sendAckTimeoutMs = sendAckTimeoutMs;
    }

    /** Open new sessions, close removed ones, reopen changed ones. Hot-applies. */
    public synchronized void reconcile(Collection<DeviceSessionConfig> desired) {
        Map<String, DeviceSessionConfig> want = new LinkedHashMap<>();
        for (DeviceSessionConfig cfg : desired) {
            want.put(cfg.deviceId(), cfg);
        }
        for (String id : new HashSet<>(sessions.keySet())) {
            if (!want.containsKey(id)) {
                closeSession(id);
            }
        }
        for (DeviceSessionConfig cfg : want.values()) {
            DeviceSession existing = sessions.get(cfg.deviceId());
            if (existing == null) {
                openSession(cfg);
            } else if (!existing.config().equals(cfg)) {
                log.info("device {} session config changed; reopening", cfg.deviceId());
                closeSession(cfg.deviceId());
                openSession(cfg);
            }
        }
        reconcileAcceptors(want.values());
    }

    /** Live status of every open session, sorted by deviceId — feeds AppliedStatus.sessions. */
    public List<DeviceSessionStatus> statuses() {
        return sessions.values().stream()
                .map(DeviceSession::status)
                .sorted(Comparator.comparing(DeviceSessionStatus::deviceId))
                .toList();
    }

    public Set<String> activeDeviceIds() {
        return Set.copyOf(sessions.keySet());
    }

    /**
     * Hand an outbound message to the device's live session — the outbound activity calls this for
     * PERSISTENT TCP devices. Throws if the device has no session yet, so the activity retries
     * until reconcile has opened it (and Temporal keeps the message durable meanwhile).
     */
    public void send(String deviceId, byte[] payload) {
        DeviceSession session = sessions.get(deviceId);
        if (session == null) {
            throw new SessionSendException("no persistent session for device " + deviceId);
        }
        session.send(payload);
    }

    private void openSession(DeviceSessionConfig cfg) {
        Consumer<byte[]> frameSink = frame -> inboundSink.accept(cfg, frame);
        DeviceSession session = new DeviceSession(
                cfg, connectExecutor, scheduler, connectTimeoutMs, minBackoffMs, maxBackoffMs,
                sendAckTimeoutMs, frameSink);
        sessions.put(cfg.deviceId(), session);
        session.start();
        log.info("opened persistent session for device {} ({} {}:{})", cfg.deviceId(),
                cfg.session().role(), cfg.host(), cfg.session().port());
    }

    private void closeSession(String deviceId) {
        DeviceSession session = sessions.remove(deviceId);
        if (session != null) {
            session.close();
            log.info("closed persistent session for device {}", deviceId);
        }
    }

    // ---- SERVER acceptors: one listen port can be shared by many devices, demuxed by handshake ----

    /** One acceptor per distinct SERVER listen port; close acceptors whose port is no longer used. */
    private void reconcileAcceptors(Collection<DeviceSessionConfig> desired) {
        Set<Integer> wantPorts = desired.stream()
                .filter(c -> c.session().role() == TcpSession.Role.SERVER && c.session().listenPort() != null)
                .map(c -> c.session().listenPort())
                .collect(Collectors.toSet());
        for (Integer port : new HashSet<>(acceptors.keySet())) {
            if (!wantPorts.contains(port)) {
                closeAcceptor(port);
            }
        }
        for (Integer port : wantPorts) {
            acceptors.computeIfAbsent(port, this::openAcceptor);
        }
    }

    private ServerSocket openAcceptor(int port) {
        try {
            ServerSocket server = new ServerSocket(port);
            server.setSoTimeout(ACCEPT_IDLE_MS);
            connectExecutor.execute(() -> acceptLoop(port, server));
            log.info("persistent SERVER acceptor listening on port {}", port);
            return server;
        } catch (IOException e) {
            // No mapping is stored (computeIfAbsent ignores null) — the next reconcile retries.
            log.error("cannot bind persistent SERVER listen port {}: {}", port, e.getMessage());
            return null;
        }
    }

    private void acceptLoop(int port, ServerSocket server) {
        while (!server.isClosed()) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (SocketTimeoutException e) {
                continue; // wake to re-check isClosed()
            } catch (IOException e) {
                if (!server.isClosed()) {
                    log.warn("persistent SERVER accept failed on port {}: {}", port, e.getMessage());
                }
                return;
            }
            // Route off the accept thread: the handshake read must not block accepting other devices.
            connectExecutor.execute(() -> routeAcceptedSocket(port, socket));
        }
    }

    /** Hand the accepted socket to its device: direct for a dedicated port, else demux by handshake. */
    private void routeAcceptedSocket(int port, Socket socket) {
        List<DeviceSession> onPort = sessions.values().stream()
                .filter(s -> s.config().session().role() == TcpSession.Role.SERVER
                        && Objects.equals(s.config().session().listenPort(), port))
                .toList();
        if (onPort.isEmpty()) {
            closeQuietly(socket);
            return;
        }
        DeviceSession target;
        if (onPort.size() == 1 && isBlank(onPort.get(0).config().session().handshakeId())) {
            target = onPort.get(0); // dedicated port — no handshake needed
        } else {
            String handshake = readHandshakeLine(socket);
            if (handshake == null) {
                log.warn("persistent SERVER port {}: no handshake from {}; dropping",
                        port, socket.getRemoteSocketAddress());
                closeQuietly(socket);
                return;
            }
            target = onPort.stream()
                    .filter(s -> handshake.equals(s.config().session().handshakeId()))
                    .findFirst().orElse(null);
            if (target == null) {
                log.warn("persistent SERVER port {}: no device matches handshake '{}'; dropping", port, handshake);
                closeQuietly(socket);
                return;
            }
        }
        target.serveAcceptedSocket(socket);
    }

    /** Read the device's newline-terminated handshake id raw, so the session keeps the rest of the stream. */
    private static String readHandshakeLine(Socket socket) {
        try {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            InputStream in = socket.getInputStream();
            StringBuilder sb = new StringBuilder();
            int b;
            while ((b = in.read()) >= 0) {
                if (b == '\n') {
                    return sb.toString().trim();
                }
                if (sb.length() >= 256) {
                    return null; // unreasonable handshake length
                }
                sb.append((char) (b & 0xFF));
            }
            return null; // EOF before a newline
        } catch (IOException e) {
            return null;
        }
    }

    private void closeAcceptor(int port) {
        ServerSocket server = acceptors.remove(port);
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // best-effort
            }
            log.info("persistent SERVER acceptor on port {} closed", port);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @PreDestroy
    public void shutdown() {
        reconcile(List.of());
        connectExecutor.shutdownNow();
        scheduler.shutdownNow();
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
