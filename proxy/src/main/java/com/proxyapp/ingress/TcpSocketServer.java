package com.proxyapp.ingress;

import com.proxyapp.routing.model.TcpProtocol;
import com.proxyapp.routing.model.Transport;
import com.proxyapp.routing.WireString;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TCP ingress: the listen port is the channel. Accept loops run on a dedicated executor
 * (never Tomcat or Temporal poller threads); the Reconciler opens/closes ports as config
 * changes and hot-swaps each port's wire protocol without dropping the ServerSocket.
 *
 * <p>Wire behavior per port comes from the binding's {@link TcpProtocol}:
 * <ul>
 *   <li><b>Legacy (null protocol or no endDelimiter):</b> device writes the payload and
 *       half-closes; one message per connection; reply {@code ACK <activityId>\n} only
 *       after Temporal accepted the enqueue, else {@code ERR ...\n} (or the configured
 *       ack/nak templates).</li>
 *   <li><b>Framed (endDelimiter set):</b> persistent connections carrying multiple
 *       frames; noise before a start delimiter is discarded; each frame is enqueued and
 *       acked/nacked individually (ack-after-enqueue per frame); a rejected frame nacks
 *       and the loop continues.</li>
 * </ul>
 *
 * <p>Connections snapshot their protocol once at accept — a hot config change applies to
 * new connections, never mid-conversation.
 */
public class TcpSocketServer {

    private static final Logger log = LoggerFactory.getLogger(TcpSocketServer.class);
    private static final int MAX_FRAME_BYTES = 10 * 1024 * 1024;
    /** While seeking a start delimiter, don't hoard unbounded noise. */
    private static final int NOISE_COMPACT_THRESHOLD = 8 * 1024;

    private record Listener(ServerSocket socket, AtomicReference<TcpProtocol> protocol) {
    }

    private final InboundSink sink;
    private final int idleTimeoutMs;
    private final int frameTimeoutMs;
    private final AtomicInteger threadSeq = new AtomicInteger();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tcp-ingress-" + threadSeq.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    private final Map<Integer, Listener> listeners = new ConcurrentHashMap<>();

    public TcpSocketServer(InboundSink sink) {
        // Idle gaps between frames are normal for persistent device connections (5 min);
        // a stall mid-frame means a sick peer (15s).
        this(sink, 300_000, 15_000);
    }

    TcpSocketServer(InboundSink sink, int idleTimeoutMs, int frameTimeoutMs) {
        this.sink = sink;
        this.idleTimeoutMs = idleTimeoutMs;
        this.frameTimeoutMs = frameTimeoutMs;
    }

    /** Desired port -> wire protocol (null value = legacy). Hot-applies. */
    public synchronized void reconcile(Map<Integer, TcpProtocol> desired) {
        for (Integer port : new HashSet<>(listeners.keySet())) {
            if (!desired.containsKey(port)) {
                closeListener(port);
            }
        }
        for (Map.Entry<Integer, TcpProtocol> entry : desired.entrySet()) {
            Listener existing = listeners.get(entry.getKey());
            if (existing != null) {
                if (!Objects.equals(existing.protocol().get(), entry.getValue())) {
                    existing.protocol().set(entry.getValue());
                    log.info("TCP ingress port {} wire protocol hot-swapped (existing connections keep the old one)",
                            entry.getKey());
                }
            } else {
                Listener opened = openListener(entry.getKey(), entry.getValue());
                if (opened != null) {
                    listeners.put(entry.getKey(), opened);
                }
            }
        }
    }

    public Set<Integer> activePorts() {
        return Set.copyOf(listeners.keySet());
    }

    /** Test hook: the live ServerSocket for a port, to assert hot-swaps don't rebind. */
    ServerSocket listenerSocket(int port) {
        Listener listener = listeners.get(port);
        return listener == null ? null : listener.socket();
    }

    private Listener openListener(int port, TcpProtocol protocol) {
        try {
            ServerSocket serverSocket = openServerSocket(port);
            Listener listener = new Listener(serverSocket, new AtomicReference<>(protocol));
            executor.execute(() -> acceptLoop(listener, port));
            log.info("TCP ingress listening on port {} ({})", port,
                    protocol == null ? "legacy wire protocol" : "custom wire protocol");
            return listener;
        } catch (IOException e) {
            log.error("cannot open TCP ingress port {}: {}", port, e.getMessage());
            return null;
        }
    }

    /** Test seam: lets a test inject a ServerSocket whose accept() simulates a transient error. */
    ServerSocket openServerSocket(int port) throws IOException {
        return new ServerSocket(port);
    }

    private void closeListener(int port) {
        Listener listener = listeners.remove(port);
        if (listener != null) {
            try {
                listener.socket().close();
            } catch (IOException ignored) {
                // closing is best-effort
            }
            log.info("TCP ingress port {} closed", port);
        }
    }

    private void acceptLoop(Listener listener, int port) {
        ServerSocket serverSocket = listener.socket();
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                TcpProtocol snapshot = listener.protocol().get(); // fixed for this connection
                executor.execute(() -> handleConnection(socket, port, snapshot));
            } catch (IOException e) {
                if (serverSocket.isClosed()) {
                    break; // closed by closeListener — normal shutdown
                }
                // Transient accept error (e.g. fd exhaustion, peer reset during accept): keep the
                // listener alive — returning here would silently stop ingress with version unchanged.
                log.warn("accept failed on TCP port {}: {} — keeping listener up", port, e.getMessage());
                sleepBackoff();
            }
        }
    }

    /** Brief pause so a persistent accept error (e.g. fd exhaustion) doesn't hot-spin the loop. */
    private static void sleepBackoff() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleConnection(Socket socket, int port, TcpProtocol protocol) {
        try (socket) {
            if (protocol == null || !protocol.isFramed()) {
                handleEofFramed(socket, port, protocol);
            } else {
                handleDelimited(socket, port, protocol);
            }
        } catch (IOException e) {
            log.warn("TCP connection on port {} dropped: {}", port, e.getMessage());
        }
    }

    /** Legacy shape: one message per connection, terminated by the device's half-close. */
    private void handleEofFramed(Socket socket, int port, TcpProtocol protocol) throws IOException {
        socket.setSoTimeout(frameTimeoutMs);
        byte[] raw = socket.getInputStream().readAllBytes();
        byte[] reply = dispatch(port, raw, protocol);
        socket.getOutputStream().write(reply);
        socket.getOutputStream().flush();
    }

    /** Framed: persistent connection, frames delimited by start/end, acked per frame. */
    private void handleDelimited(Socket socket, int port, TcpProtocol protocol) throws IOException {
        byte[] start = protocol.startDelimiter() != null
                ? WireString.decode(protocol.startDelimiter()) : null;
        byte[] end = WireString.decode(protocol.endDelimiter());
        InputStream in = new BufferedInputStream(socket.getInputStream());
        OutputStream out = socket.getOutputStream();
        FrameBuffer buf = new FrameBuffer();
        boolean seekingStart = start != null;
        boolean atBoundary = true;

        while (true) {
            socket.setSoTimeout(atBoundary ? idleTimeoutMs : frameTimeoutMs);
            int b;
            try {
                b = in.read();
            } catch (SocketTimeoutException e) {
                if (atBoundary) {
                    log.debug("TCP port {} idle connection closed", port);
                } else {
                    log.warn("TCP port {} timed out mid-frame after {} bytes", port, buf.size());
                }
                return;
            }
            if (b < 0) {
                if (!atBoundary && buf.size() > 0) {
                    log.warn("TCP port {} peer closed mid-frame; discarding {} bytes", port, buf.size());
                }
                return;
            }
            buf.append((byte) b);
            atBoundary = false;

            if (seekingStart) {
                if (buf.endsWith(start)) {
                    buf.reset(); // discard the delimiter (and any noise before it)
                    seekingStart = false;
                } else if (buf.size() > NOISE_COMPACT_THRESHOLD) {
                    buf.compactKeepLast(start.length - 1);
                }
                continue;
            }

            if (buf.endsWith(end)) {
                byte[] frame = buf.toArray(buf.size() - end.length);
                byte[] reply = dispatch(port, frame, protocol);
                out.write(reply);
                out.flush();
                buf.reset();
                seekingStart = start != null;
                atBoundary = true;
                continue;
            }

            if (buf.size() > MAX_FRAME_BYTES) {
                log.error("TCP port {} frame exceeded {} bytes; closing connection", port, MAX_FRAME_BYTES);
                out.write(nakBytes(protocol, "FRAME_TOO_LARGE", ""));
                out.flush();
                return; // delimiter resync can't be trusted after a runaway frame
            }
        }
    }

    /** Enqueue one message; ack only after Temporal accepted it (ack-after-enqueue). */
    private byte[] dispatch(int port, byte[] raw, TcpProtocol protocol) {
        try {
            InboundGateway.EnqueueResult result =
                    sink.accept(Transport.TCP, Integer.toString(port), null, raw);
            return ackBytes(protocol, result.activityId());
        } catch (IngressException e) {
            return nakBytes(protocol, e.reason().name(), e.getMessage());
        } catch (Exception e) {
            log.error("TCP ingress on port {} failed", port, e);
            return nakBytes(protocol, "INTERNAL", "");
        }
    }

    private static byte[] ackBytes(TcpProtocol protocol, String activityId) {
        if (protocol != null && protocol.ackReply() != null) {
            return WireString.decodeToString(protocol.ackReply())
                    .replace("{activityId}", activityId)
                    .replace("{reason}", "")
                    .getBytes(StandardCharsets.ISO_8859_1);
        }
        return ("ACK " + activityId + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] nakBytes(TcpProtocol protocol, String reason, String message) {
        if (protocol != null && protocol.nakReply() != null) {
            return WireString.decodeToString(protocol.nakReply())
                    .replace("{reason}", reason)
                    .replace("{activityId}", "")
                    .getBytes(StandardCharsets.ISO_8859_1);
        }
        String legacy = message == null || message.isEmpty()
                ? "ERR " + reason + "\n"
                : "ERR " + reason + " " + message + "\n";
        return legacy.getBytes(StandardCharsets.UTF_8);
    }

    @PreDestroy
    public void shutdown() {
        reconcile(Map.of());
        executor.shutdownNow();
    }

    /** Growable byte buffer with cheap endsWith — avoids O(n²) toByteArray scans. */
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

        /** Keep only the last {@code n} bytes (delimiter-straddle window for noise mode). */
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
