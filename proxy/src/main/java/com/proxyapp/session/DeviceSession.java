package com.proxyapp.session;

import com.proxyapp.routing.TcpProtocol;
import com.proxyapp.routing.TcpSession;
import com.proxyapp.routing.WireString;
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
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * One persistent connection to a device. CLIENT role dials the device and keeps the socket warm,
 * reconnecting with backoff on any drop. SERVER role is passive — the {@link TcpSessionManager}
 * acceptor owns the listen port and hands this session each accepted socket (after handshake demux
 * for shared ports) via {@link #serveAcceptedSocket}. Either way the heartbeat ping and liveness
 * check run as periodic tasks on the shared scheduler and the read loop is identical.
 *
 * <p>Owned by {@link TcpSessionManager} and lives entirely in worker-process code — never a
 * Temporal workflow or activity (it does blocking socket I/O, and heartbeats must never become
 * workflow events).
 *
 * <p><b>Liveness</b> ({@code missThreshold} consecutive misses → DOWN + reconnect; any inbound
 * frame is proof of life and resets the counter):
 * <ul>
 *   <li><b>Active probe</b> — {@code sendIntervalSec} + {@code expectReply}: send a ping each
 *       interval; a miss is counted when no reply arrives within {@code replyTimeoutMs}.</li>
 *   <li><b>Passive watchdog</b> — {@code expectInboundSec}: a miss is counted for each window with
 *       no inbound frame (the device pushes its own heartbeats).</li>
 *   <li><b>Keepalive only</b> — a ping with no {@code expectReply} and no watchdog just keeps the
 *       link warm; DOWN is then detected only by TCP errors.</li>
 * </ul>
 */
final class DeviceSession {

    private static final Logger log = LoggerFactory.getLogger(DeviceSession.class);
    /** Per-beat heartbeat/ack trace — its own logger so `logging.level.heartbeat` toggles it. */
    private static final Logger hb = LoggerFactory.getLogger("heartbeat");
    private static final int MAX_FRAME_BYTES = 10 * 1024 * 1024;
    private static final int NOISE_COMPACT_THRESHOLD = 8 * 1024;
    /** Read wakeup granularity so the loop notices {@code closed} / shutdown promptly. */
    private static final int READ_IDLE_MS = 1_000;

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

    // Runtime state.
    private volatile boolean closed;
    private volatile DeviceSessionState state = DeviceSessionState.CONNECTING;
    private final AtomicReference<Socket> socket = new AtomicReference<>();
    private final AtomicReference<OutputStream> out = new AtomicReference<>();
    private final Object writeLock = new Object();
    private volatile long lastInboundAtMs;    // 0 = never
    private volatile boolean pingOutstanding;
    private volatile long pingDeadlineMs;
    private volatile long beats;              // heartbeats sent this connection (demo trace)
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
        String hb = lastInboundAtMs == 0 ? null : Instant.ofEpochMilli(lastInboundAtMs).toString();
        return new DeviceSessionStatus(config.deviceId(), config.session().role().name(),
                state.name(), hb, inflight.get());
    }

    void start() {
        if (role == TcpSession.Role.CLIENT) {
            connectExecutor.execute(this::runClientLoop);
        } else {
            state = DeviceSessionState.CONNECTING; // SERVER: wait for the acceptor to hand us a socket
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
        state = DeviceSessionState.DOWN;
    }

    /**
     * Write one outbound message onto the live socket and (unless fire-and-forget) await its ack.
     * Single-in-flight: one send at a time; the next inbound frame containing the configured
     * {@code expectedAck} completes it. Throws {@link SessionSendException} on a down link, a busy
     * slot, a write error, or a missing ack — the caller is the Temporal activity, so a throw means
     * the message stays durable and the activity retries.
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
                    throw new SessionSendException("device " + config.deviceId()
                            + " sent no ack within " + sendAckTimeoutMs + "ms");
                }
            }
        } catch (IOException e) {
            markDownAndReconnect();
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
            try {
                state = DeviceSessionState.CONNECTING;
                s = new Socket();
                s.connect(new InetSocketAddress(config.host(), config.session().port()), connectTimeoutMs);
                s.setSoTimeout(READ_IDLE_MS);
                onConnected(s);
                backoff = minBackoffMs; // a good connect resets the backoff
                readLoop(s);
            } catch (IOException e) {
                if (!closed) {
                    log.debug("device {} connect/read ended: {}", config.deviceId(), e.getMessage());
                }
            } finally {
                closeSocket(s);
                socket.compareAndSet(s, null);
                out.set(null);
                if (!closed) {
                    state = DeviceSessionState.DOWN;
                }
            }
            if (closed) {
                break;
            }
            sleep(backoff);
            backoff = Math.min(maxBackoffMs, backoff * 2);
        }
        state = DeviceSessionState.DOWN;
    }

    /**
     * SERVER: serve a socket the manager's acceptor handed us (already demuxed to this device by
     * listen port + handshake). A fresh dial-in supersedes the current connection; on drop the link
     * goes DOWN until the device dials in again.
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
        try {
            s.setSoTimeout(READ_IDLE_MS);
            onConnected(s);
            readLoop(s);
        } catch (IOException e) {
            if (!closed) {
                log.debug("device {} session read ended: {}", config.deviceId(), e.getMessage());
            }
        } finally {
            closeSocket(s);
            socket.compareAndSet(s, null);
            out.set(null);
            if (!closed) {
                state = DeviceSessionState.DOWN;
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
        state = DeviceSessionState.UP;
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
                } else if (buf.size() > NOISE_COMPACT_THRESHOLD) {
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
            if (buf.size() > MAX_FRAME_BYTES) {
                log.error("device {} frame exceeded {} bytes; dropping link", config.deviceId(), MAX_FRAME_BYTES);
                return;
            }
        }
    }

    /**
     * Any inbound frame is proof of life. A frame carrying a pending send's {@code expectedAck}
     * completes that send; one carrying {@code expectReply} answers a ping.
     */
    private void onFrame(byte[] frame) {
        lastInboundAtMs = nowMs();
        consecutiveMisses.set(0);
        synchronized (ackLock) {
            if (pendingAck != null && contains(frame, frame.length, pendingAck)) {
                ackReceived = true;
                ackLock.notifyAll();
                hb.info("{} <- ACK (send complete)", config.deviceId());
                return;
            }
        }
        if (expectReply != null && contains(frame, frame.length, expectReply)) {
            pingOutstanding = false;
            hb.info("{} <- PONG #{} (link up {})", config.deviceId(), beats, uptime());
            return;
        }
        // Unsolicited device -> cloud frame: hand to the inbound sink (-> DeliverToCloud). A failed
        // enqueue must not drop the link, so swallow and let the device re-send if it retries.
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
            markDownAndReconnect();
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
            markDownAndReconnect();
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

    private void markDownAndReconnect() {
        state = DeviceSessionState.DOWN;
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

    private static boolean contains(byte[] haystack, int size, byte[] needle) {
        if (needle.length == 0 || needle.length > size) {
            return false;
        }
        for (int from = 0; from <= size - needle.length; from++) {
            boolean match = true;
            for (int i = 0; i < needle.length; i++) {
                if (haystack[from + i] != needle[i]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
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

    /** Human "link up" duration since the current connection was established (for the demo trace). */
    private String uptime() {
        long s = Math.max(0, nowMs() - connectedAtMs) / 1000;
        return s < 60 ? s + "s" : (s / 60) + "m" + (s % 60) + "s";
    }

    /** Growable byte buffer with cheap endsWith — same shape as TcpSocketServer's frame reader. */
    private static final class FrameBuffer {
        private byte[] data = new byte[1024];
        private int size;

        void append(byte b) {
            if (size == data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = b;
        }

        boolean endsWith(byte[] suffix) {
            if (size < suffix.length) {
                return false;
            }
            for (int i = 0; i < suffix.length; i++) {
                if (data[size - suffix.length + i] != suffix[i]) {
                    return false;
                }
            }
            return true;
        }

        byte[] toArray(int length) {
            return Arrays.copyOf(data, length);
        }

        void compactKeepLast(int n) {
            if (n <= 0) {
                size = 0;
                return;
            }
            if (size > n) {
                System.arraycopy(data, size - n, data, 0, n);
                size = n;
            }
        }

        void reset() {
            size = 0;
        }

        int size() {
            return size;
        }
    }
}
