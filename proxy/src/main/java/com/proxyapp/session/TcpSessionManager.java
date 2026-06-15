package com.proxyapp.session;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The proxy's <b>connection table</b> for persistent device links: a {@code Map<deviceId,
 * DeviceSession>}. Mirrors {@link com.proxyapp.ingress.TcpSocketServer} /
 * {@code FtpIngressListener} — the {@code Reconciler} calls {@link #reconcile} as control state
 * changes, opening new sessions, closing removed ones, and hot-replacing changed ones. Sessions
 * live outside Temporal; the outbound activity borrows them as a transport (phase 3).
 */
public class TcpSessionManager {

    private static final Logger log = LoggerFactory.getLogger(TcpSessionManager.class);

    private final int connectTimeoutMs;
    private final long minBackoffMs;
    private final long maxBackoffMs;

    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger threadSeq = new AtomicInteger();
    private final ExecutorService connectExecutor = Executors.newCachedThreadPool(
            r -> daemon(r, "tcp-session-conn-" + threadSeq.incrementAndGet()));
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            2, r -> daemon(r, "tcp-session-hb-" + threadSeq.incrementAndGet()));

    public TcpSessionManager() {
        // Dial timeout 5s; reconnect backoff 1s..30s (doubling).
        this(5_000, 1_000, 30_000);
    }

    TcpSessionManager(int connectTimeoutMs, long minBackoffMs, long maxBackoffMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.minBackoffMs = minBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
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

    private void openSession(DeviceSessionConfig cfg) {
        DeviceSession session = new DeviceSession(
                cfg, connectExecutor, scheduler, connectTimeoutMs, minBackoffMs, maxBackoffMs);
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
