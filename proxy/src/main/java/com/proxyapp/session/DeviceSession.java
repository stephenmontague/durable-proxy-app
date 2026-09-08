package com.proxyapp.session;

import com.proxyapp.routing.WireString;
import com.proxyapp.routing.model.TcpProtocol;
import com.proxyapp.routing.model.TcpSession;
import com.proxyapp.session.model.DeviceSessionConfig;
import com.proxyapp.session.model.DeviceSessionState;
import com.proxyapp.session.model.DeviceSessionStatus;
import com.proxyapp.session.model.SessionEvent;
import com.proxyapp.wire.Bytes;
import com.proxyapp.wire.FrameBuffer;
import com.proxyapp.wire.WireLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * One persistent connection to a device. CLIENT dials and keeps the socket warm, reconnecting with
 * backoff; SERVER is passive, taking accepted sockets from the {@link TcpSessionManager} acceptor.
 * Lives in worker-process code, never a workflow or activity — it blocks on socket I/O, and
 * heartbeats must never become workflow events.
 *
 * <p>Liveness: {@code missThreshold} consecutive misses go DOWN and reconnect; any inbound frame
 * resets the counter. An active probe counts a miss per unanswered ping, a passive watchdog one per
 * silent window; a ping with neither only keeps the link warm, leaving DOWN to TCP errors.
 */
final class DeviceSession {

    private static final Logger log = LoggerFactory.getLogger(DeviceSession.class);
    /** Per-beat heartbeat/ack trace — its own logger so `logging.level.heartbeat` toggles it. */
    private static final Logger hb = LoggerFactory.getLogger("heartbeat");
    /** Read wakeup granularity so the loop notices {@code closed} / shutdown promptly. */
    private static final int READ_IDLE_MS = 1_000;
    /** Per-session transition history kept for remote diagnosis (bounded ring buffer). */
    private static final int MAX_EVENTS = 10;

    private final DeviceSessionConfig config;
    private final ExecutorService connectExecutor;
    private final ScheduledExecutorService scheduler;
    private final int connectTimeoutMs;
    private final long minBackoffMs;
    private final long maxBackoffMs;
    private final long sendAckTimeoutMs;
    private final Consumer<byte[]> inboundSink; // unsolicited device→cloud frames go here

    // Framing + heartbeat payloads, decoded once.
    private final byte[] startDelim;     // nullable
    private final byte[] endDelim;       // non-null (defaults to newline)
    private final byte[] pingFrame;      // nullable: framed sendPayload, present iff a ping is configured
    private final byte[] expectReply;    // nullable: decoded expected ping reply
    private final byte[] sendExpectedAck; // ack an outbound send waits for (contains-match)
    private final boolean awaitSendReply; // false = fire-and-forget sends
    private final TcpSession.Role role;   // CLIENT dials the device; SERVER accepts its dial-in

    // Liveness config (with defaults applied).
    private final Integer sendIntervalSec;
    private final Integer expectInboundSec;
    private final int replyTimeoutMs;
    private final int missThreshold;
    private final boolean activeProbe; // sendIntervalSec != null && expectReply != null

    private volatile boolean closed;
    private volatile DeviceSessionState state = DeviceSessionState.CONNECTING;
    // Diagnostics surfaced in status(), since the local logs sit on an unreachable edge machine.
    // Guarded by eventLock.
    private final Object eventLock = new Object();
    private final Deque<SessionEvent> events = new ArrayDeque<>();
    private String lastError;        // reason of the most recent DOWN; cleared on UP, kept on CONNECTING
    private long lastTransitionAtMs; // 0 = never
    private final AtomicReference<Socket> socket = new AtomicReference<>();
    private final AtomicReference<OutputStream> out = new AtomicReference<>();
    private final Object writeLock = new Object();
    private volatile long lastInboundAtMs;    // 0 = never
    private volatile boolean pingOutstanding;
    private volatile long pingDeadlineMs;
    private volatile long beats;              // heartbeats sent this connection (heartbeat trace only)
    private volatile long connectedAtMs;      // for "link up Xs" in the heartbeat trace
    private final AtomicInteger consecutiveMisses = new AtomicInteger();
    private final AtomicInteger inflight = new AtomicInteger(); // outbound sends awaiting their ack
    private final Semaphore sendSlot = new Semaphore(1);        // single in-flight send at a time
    private final Object ackLock = new Object();
    private byte[] pendingAck;   // guarded by ackLock; non-null while a send awaits its ack
    private boolean ackReceived; // guarded by ackLock
    private volatile ScheduledFuture<?> pingTask;
    private volatile ScheduledFuture<?> livenessTask;

    DeviceSession(DeviceSessionConfig config, ExecutorService connectExecutor,
                  ScheduledExecutorService scheduler, int connectTimeoutMs,
                  long minBackoffMs, long maxBackoffMs, long sendAckTimeoutMs,
                  Consumer<byte[]> inboundSink) {
        this.config = config;
        this.connectExecutor = connectExecutor;
        this.scheduler = scheduler;
        this.connectTimeoutMs = connectTimeoutMs;
        this.minBackoffMs = minBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
        this.sendAckTimeoutMs = sendAckTimeoutMs;
        this.inboundSink = inboundSink;

        TcpProtocol p = config.protocol();
        this.startDelim = (p != null && p.startDelimiter() != null)
                ? WireString.decode(p.startDelimiter()) : null;
        this.endDelim = (p != null && p.endDelimiter() != null)
                ? WireString.decode(p.endDelimiter())
                : "\n".getBytes(StandardCharsets.ISO_8859_1);
        this.sendExpectedAck = (p != null && p.expectedAck() != null)
                ? WireString.decode(p.expectedAck())
                : "ACK".getBytes(StandardCharsets.ISO_8859_1);
        this.awaitSendReply = p == null || p.shouldAwaitReply();
        this.role = config.session().role();

        TcpSession.Heartbeat hb = config.session().heartbeat();
        this.sendIntervalSec = hb == null ? null : hb.sendIntervalSec();
        this.expectInboundSec = hb == null ? null : hb.expectInboundSec();
        this.replyTimeoutMs = (hb == null || hb.replyTimeoutMs() == null) ? 5_000 : hb.replyTimeoutMs();
        this.missThreshold = (hb == null || hb.missThreshold() == null) ? 3 : hb.missThreshold();
        this.expectReply = (hb != null && hb.expectReply() != null)
                ? WireString.decode(hb.expectReply()) : null;
        this.activeProbe = sendIntervalSec != null && expectReply != null;
        this.pingFrame = (hb != null && hb.sendPayload() != null && sendIntervalSec != null)
                ? frame(WireString.decode(hb.sendPayload())) : null;
    }

    DeviceSessionConfig config() {
        return config;
    }

    DeviceSessionStatus status() {
        String hbAt = lastInboundAtMs == 0 ? null : Instant.ofEpochMilli(lastInboundAtMs).toString();
        DeviceSessionState st;
        String err;
        String transitionAt;
        List<SessionEvent> recent;
        synchronized (eventLock) {
            st = state;
            err = lastError;
            transitionAt = lastTransitionAtMs == 0
                    ? null : Instant.ofEpochMilli(lastTransitionAtMs).toString();
            recent = List.copyOf(events);
        }
        return new DeviceSessionStatus(config.deviceId(), config.session().role().name(),
                st.name(), hbAt, inflight.get(), err, transitionAt, recent);
    }

    /**
     * The single writer for {@link #state}, and a no-op when unchanged so a specific fault reason isn't
     * clobbered by the read loop's follow-up DOWN. DOWN sets {@link #lastError}, UP clears it,
     * CONNECTING preserves it. Leaving UP also wakes a send parked in its ack wait, failing it at once
     * instead of burning {@code sendAckTimeoutMs}; that notify is sent after {@code eventLock} is
     * released, so the lock order is only ever eventLock → ackLock.
     */
    private void transition(DeviceSessionState newState, String detail) {
        boolean leftUp;
        synchronized (eventLock) {
            if (newState == state) {
                return;
            }
            leftUp = state == DeviceSessionState.UP;
            state = newState;
            lastTransitionAtMs = nowMs();
            if (newState == DeviceSessionState.DOWN) {
                lastError = detail;
            } else if (newState == DeviceSessionState.UP) {
                lastError = null;
            }
            events.addLast(new SessionEvent(
                    Instant.ofEpochMilli(lastTransitionAtMs).toString(), newState.name(), detail));
            while (events.size() > MAX_EVENTS) {
                events.removeFirst();
            }
        }
        if (leftUp) {
            synchronized (ackLock) {
                ackLock.notifyAll();
            }
        }
    }

    void start() {
        if (role == TcpSession.Role.CLIENT) {
            connectExecutor.execute(this::runClientLoop);
        } else {
            // SERVER: wait for the acceptor to hand us a socket
            transition(DeviceSessionState.CONNECTING, "awaiting device dial-in");
        }
        if (pingFrame != null) {
            long periodMs = sendIntervalSec * 1_000L;
            pingTask = scheduler.scheduleAtFixedRate(this::sendPing, periodMs, periodMs, TimeUnit.MILLISECONDS);
        }
        long checkMs = livenessCheckPeriodMs();
        if (checkMs > 0) {
            livenessTask = scheduler.scheduleAtFixedRate(
                    this::checkLiveness, checkMs, checkMs, TimeUnit.MILLISECONDS);
        }
    }

    void close() {
        closed = true;
        cancel(pingTask);
        cancel(livenessTask);
        closeSocket(socket.getAndSet(null));
        transition(DeviceSessionState.DOWN, "session closed");
    }

    /**
     * Write one outbound message onto the live socket and, unless fire-and-forget, await its ack.
     * Single-in-flight: the next inbound frame containing {@code expectedAck} completes it. Throws
     * {@link SessionSendException} on a down link, busy slot, write error, or missing ack — the caller
     * is the Temporal activity, so a throw keeps the message durable and retries.
     */
    void send(byte[] payload) {
        boolean acquired;
        try {
            acquired = sendSlot.tryAcquire(sendAckTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SessionSendException("device " + config.deviceId() + " send interrupted");
        }
        if (!acquired) {
            throw new SessionSendException("device " + config.deviceId() + " send slot busy");
        }
        inflight.incrementAndGet();
        try {
            if (state != DeviceSessionState.UP) {
                throw new SessionSendException("device " + config.deviceId() + " link is " + state);
            }
            if (!awaitSendReply) {
                writeFrame(frame(payload)); // fire-and-forget: the TCP write is the guarantee
                return;
            }
            synchronized (ackLock) {
                pendingAck = sendExpectedAck;
                ackReceived = false;
            }
            writeFrame(frame(payload));
            synchronized (ackLock) {
                long deadline = nowMs() + sendAckTimeoutMs;
                long remaining;
                while (!ackReceived && (remaining = deadline - nowMs()) > 0
                        && state == DeviceSessionState.UP) {
                    ackLock.wait(remaining);
                }
                if (!ackReceived) {
                    // transition() wakes us the moment the link leaves UP, so tell "device went away
                    // mid-send" apart from "device stayed up but never acked".
                    throw new SessionSendException(state == DeviceSessionState.UP
                            ? "device " + config.deviceId() + " sent no ack within "
                                    + sendAckTimeoutMs + "ms"
                            : "device " + config.deviceId() + " link went " + state
                                    + " before its send was acked");
                }
            }
        } catch (IOException e) {
            markDownAndReconnect("send failed: " + e.getMessage());
            throw new SessionSendException("device " + config.deviceId() + " send failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SessionSendException("device " + config.deviceId() + " send interrupted awaiting ack");
        } finally {
            synchronized (ackLock) {
                pendingAck = null;
            }
            inflight.decrementAndGet();
            sendSlot.release();
        }
    }

    // ---- connect / read loop (connect executor thread) ----

    /** CLIENT: dial the device, reconnecting with backoff after any drop. */
    private void runClientLoop() {
        long backoff = minBackoffMs;
        while (!closed) {
            Socket s = null;
            String dropReason = "peer closed connection";
            try {
                transition(DeviceSessionState.CONNECTING,
                        "dialing " + config.host() + ":" + config.session().port());
                s = new Socket();
                s.connect(new InetSocketAddress(config.host(), config.session().port()), connectTimeoutMs);
                s.setSoTimeout(READ_IDLE_MS);
                onConnected(s);
                backoff = minBackoffMs; // a good connect resets the backoff
                readLoop(s);
            } catch (IOException e) {
                dropReason = "connect/read ended: " + e.getMessage();
                if (!closed) {
                    log.debug("device {} connect/read ended: {}", config.deviceId(), e.getMessage());
                }
            } finally {
                closeSocket(s);
                socket.compareAndSet(s, null);
                out.set(null);
                if (!closed) {
                    transition(DeviceSessionState.DOWN, dropReason);
                }
            }
            if (closed) {
                break;
            }
            sleep(backoff);
            backoff = Math.min(maxBackoffMs, backoff * 2);
        }
        transition(DeviceSessionState.DOWN, "connect loop stopped");
    }

    /**
     * SERVER: serve a socket the acceptor already demuxed to this device. A fresh dial-in supersedes
     * the current connection; on drop the link stays DOWN until the device dials in again.
     */
    void serveAcceptedSocket(Socket s) {
        if (closed) {
            closeSocket(s);
            return;
        }
        closeSocket(socket.getAndSet(null)); // a new connection supersedes the current one
        connectExecutor.execute(() -> serve(s));
    }

    private void serve(Socket s) {
        String dropReason = "peer closed connection";
        try {
            s.setSoTimeout(READ_IDLE_MS);
            onConnected(s);
            readLoop(s);
        } catch (IOException e) {
            dropReason = "session read ended: " + e.getMessage();
            if (!closed) {
                log.debug("device {} session read ended: {}", config.deviceId(), e.getMessage());
            }
        } finally {
            closeSocket(s);
            socket.compareAndSet(s, null);
            out.set(null);
            if (!closed) {
                transition(DeviceSessionState.DOWN, dropReason);
            }
        }
    }

    private void onConnected(Socket s) throws IOException {
        socket.set(s);
        out.set(s.getOutputStream());
        lastInboundAtMs = nowMs(); // grace: treat the fresh connect as recent proof of life
        consecutiveMisses.set(0);
        pingOutstanding = false;
        beats = 0;
        connectedAtMs = nowMs();
        transition(DeviceSessionState.UP, "link up (" + s.getRemoteSocketAddress() + ")");
        log.info("device {} session UP ({})", config.deviceId(), s.getRemoteSocketAddress());
    }

    private void readLoop(Socket s) throws IOException {
        InputStream in = new BufferedInputStream(s.getInputStream());
        FrameBuffer buf = new FrameBuffer();
        boolean seekingStart = startDelim != null;
        while (!closed) {
            int b;
            try {
                b = in.read();
            } catch (SocketTimeoutException e) {
                continue; // idle gap between frames is normal; loop to re-check closed
            }
            if (b < 0) {
                return; // peer closed
            }
            buf.append((byte) b);

            if (seekingStart) {
                if (buf.endsWith(startDelim)) {
                    buf.reset();
                    seekingStart = false;
                } else if (buf.size() > WireLimits.NOISE_COMPACT_THRESHOLD) {
                    buf.compactKeepLast(startDelim.length - 1);
                }
                continue;
            }
            if (buf.endsWith(endDelim)) {
                onFrame(buf.toArray(buf.size() - endDelim.length));
                buf.reset();
                seekingStart = startDelim != null;
                continue;
            }
            if (buf.size() > WireLimits.MAX_FRAME_BYTES) {
                log.error("device {} frame exceeded {} bytes; dropping link",
                        config.deviceId(), WireLimits.MAX_FRAME_BYTES);
                return;
            }
        }
    }

    /** Any inbound frame is proof of life; one carrying {@code expectedAck} completes a pending send,
     *  one carrying {@code expectReply} answers a ping. */
    private void onFrame(byte[] frame) {
        lastInboundAtMs = nowMs();
        consecutiveMisses.set(0);
        synchronized (ackLock) {
            if (pendingAck != null && Bytes.contains(frame, frame.length, pendingAck)) {
                ackReceived = true;
                ackLock.notifyAll();
                hb.info("{} <- ACK (send complete)", config.deviceId());
                return;
            }
        }
        if (expectReply != null && Bytes.contains(frame, frame.length, expectReply)) {
            pingOutstanding = false;
            hb.info("{} <- PONG #{} (link up {})", config.deviceId(), beats, uptime());
            return;
        }
        // A failed enqueue must not drop the link, so swallow and let the device re-send.
        try {
            inboundSink.accept(frame);
        } catch (RuntimeException e) {
            log.warn("device {} inbound enqueue failed: {}", config.deviceId(), e.getMessage());
        }
    }

    // ---- heartbeat + liveness (scheduler threads) ----

    private void sendPing() {
        if (closed || state != DeviceSessionState.UP) {
            return;
        }
        try {
            writeFrame(pingFrame);
            beats++;
            hb.info("{} -> PING #{}", config.deviceId(), beats);
            if (activeProbe) {
                pingOutstanding = true;
                pingDeadlineMs = nowMs() + replyTimeoutMs;
            }
        } catch (IOException e) {
            log.debug("device {} ping write failed: {}", config.deviceId(), e.getMessage());
            markDownAndReconnect("ping write failed: " + e.getMessage());
        }
    }

    private void checkLiveness() {
        if (closed || state != DeviceSessionState.UP) {
            return;
        }
        boolean miss;
        if (activeProbe) {
            // A miss is one ping whose reply didn't land in time; count it once.
            if (pingOutstanding && nowMs() >= pingDeadlineMs) {
                pingOutstanding = false;
                miss = true;
            } else {
                return;
            }
        } else if (expectInboundSec != null) {
            miss = lastInboundAtMs == 0 || nowMs() - lastInboundAtMs >= expectInboundSec * 1_000L;
            if (!miss) {
                return;
            }
        } else {
            return; // keepalive only — no miss-based liveness
        }
        int m = consecutiveMisses.incrementAndGet();
        log.debug("device {} heartbeat miss {}/{}", config.deviceId(), m, missThreshold);
        if (m >= missThreshold) {
            log.warn("device {} link DOWN after {} missed heartbeat(s)", config.deviceId(), m);
            markDownAndReconnect("link down after " + m + " missed heartbeat(s)");
        }
    }

    /** How often to evaluate liveness: per reply-deadline for active probe, per window for watchdog. */
    private long livenessCheckPeriodMs() {
        if (activeProbe) {
            return Math.max(100L, Math.min(replyTimeoutMs, sendIntervalSec * 1_000L));
        }
        if (expectInboundSec != null) {
            return expectInboundSec * 1_000L;
        }
        return 0;
    }

    private void markDownAndReconnect(String reason) {
        transition(DeviceSessionState.DOWN, reason);
        closeSocket(socket.get()); // unblocks the read loop, which then reconnects with backoff
    }

    // ---- helpers ----

    private void writeFrame(byte[] bytes) throws IOException {
        OutputStream o = out.get();
        if (o == null) {
            throw new IOException("not connected");
        }
        synchronized (writeLock) {
            o.write(bytes);
            o.flush();
        }
    }

    private byte[] frame(byte[] payload) {
        int len = (startDelim != null ? startDelim.length : 0) + payload.length + endDelim.length;
        byte[] framed = new byte[len];
        int i = 0;
        if (startDelim != null) {
            System.arraycopy(startDelim, 0, framed, i, startDelim.length);
            i += startDelim.length;
        }
        System.arraycopy(payload, 0, framed, i, payload.length);
        i += payload.length;
        System.arraycopy(endDelim, 0, framed, i, endDelim.length);
        return framed;
    }

    private static void closeSocket(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    private static void cancel(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long nowMs() {
        return System.currentTimeMillis();
    }

    /** Human "link up" duration since the current connection was established, for the {@code hb} trace. */
    private String uptime() {
        long s = Math.max(0, nowMs() - connectedAtMs) / 1000;
        return s < 60 ? s + "s" : (s / 60) + "m" + (s % 60) + "s";
    }

}
