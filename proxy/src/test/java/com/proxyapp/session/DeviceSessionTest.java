package com.proxyapp.session;

import com.proxyapp.routing.TcpProtocol;
import com.proxyapp.routing.TcpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CLIENT-mode session behavior against a stub server: connect→UP, reconnect on drop, and
 * UP↔DOWN transitions driven by missed heartbeats (both the passive watchdog and the active
 * ping/expect-reply probe). Intervals come from config in seconds, so a few tests run a couple of
 * real seconds — backoff is dialed down so reconnects are quick.
 */
class DeviceSessionTest {

    private ExecutorService connectExecutor;
    private ScheduledExecutorService scheduler;
    private final List<byte[]> inboundFrames = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        connectExecutor = Executors.newCachedThreadPool(daemon("conn"));
        scheduler = Executors.newScheduledThreadPool(2, daemon("hb"));
    }

    @AfterEach
    void tearDown() {
        connectExecutor.shutdownNow();
        scheduler.shutdownNow();
    }

    @Test
    void connectsAndReportsUp() throws Exception {
        try (StubTcpServer server = new StubTcpServer(StubTcpServer::silent)) {
            DeviceSession session = clientSession(server.port(), watchdog(2, 3), null);
            session.start();
            awaitState(session, DeviceSessionState.UP, 2_000);
            session.close();
        }
    }

    @Test
    void reconnectsAfterATransientDrop() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        try (StubTcpServer server = new StubTcpServer(socket -> {
            if (connections.incrementAndGet() == 1) {
                closeQuietly(socket);       // drop the first connection
            } else {
                StubTcpServer.silent(socket); // keep later ones alive
            }
        })) {
            DeviceSession session = clientSession(server.port(), watchdog(5, 3), null);
            session.start();
            awaitTrue(() -> connections.get() >= 2, 4_000); // it dialed again after the drop
            awaitState(session, DeviceSessionState.UP, 3_000);
            session.close();
        }
    }

    @Test
    void goesDownAfterMissedInboundHeartbeats() throws Exception {
        // Accept once and stay silent; the watchdog must flip the link DOWN and reconnects refuse.
        try (StubTcpServer server = new StubTcpServer(StubTcpServer::silent, true)) {
            DeviceSession session = clientSession(server.port(), watchdog(1, 2), null);
            session.start();
            awaitState(session, DeviceSessionState.UP, 2_000);
            awaitState(session, DeviceSessionState.DOWN, 6_000);
            session.close();
        }
    }

    @Test
    void activeProbeStaysUpWhilePingsAreAnswered() throws Exception {
        try (StubTcpServer server = new StubTcpServer(StubTcpServer::pong)) {
            DeviceSession session = clientSession(server.port(),
                    new TcpSession.Heartbeat(1, "PING", "PONG", 300, null, 2), null);
            session.start();
            awaitState(session, DeviceSessionState.UP, 2_000);
            Thread.sleep(2_500); // more than two ping cycles
            assertThat(session.status().state()).isEqualTo("UP");
            session.close();
        }
    }

    @Test
    void activeProbeGoesDownWhenPingsAreUnanswered() throws Exception {
        try (StubTcpServer server = new StubTcpServer(StubTcpServer::silent, true)) {
            DeviceSession session = clientSession(server.port(),
                    new TcpSession.Heartbeat(1, "PING", "PONG", 300, null, 2), null);
            session.start();
            awaitState(session, DeviceSessionState.UP, 2_000);
            awaitState(session, DeviceSessionState.DOWN, 6_000);
            session.close();
        }
    }

    @Test
    void sendDeliversFramedPayloadAndAwaitsAck() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        try (StubTcpServer server = new StubTcpServer(socket -> ackResponder(socket, received))) {
            DeviceSession session = clientSession(server.port(), watchdog(60, 5), null);
            session.start();
            awaitState(session, DeviceSessionState.UP, 2_000);
            session.send("HELLO".getBytes(StandardCharsets.ISO_8859_1)); // returns once the ACK arrives
            assertThat(received).contains("HELLO");
            session.close();
        }
    }

    @Test
    void sendThrowsWhenNoAckArrives() throws Exception {
        try (StubTcpServer server = new StubTcpServer(StubTcpServer::silent)) {
            DeviceSession session = clientSession(server.port(), watchdog(60, 5), null);
            session.start();
            awaitState(session, DeviceSessionState.UP, 2_000);
            assertThatThrownBy(() -> session.send("HELLO".getBytes(StandardCharsets.ISO_8859_1)))
                    .isInstanceOf(SessionSendException.class);
            session.close();
        }
    }

    @Test
    void fireAndForgetSendReturnsWithoutAck() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        // awaitReply=false -> the session writes and returns without waiting for any reply
        TcpProtocol fireAndForget = new TcpProtocol(null, "<LF>", null, null, null, false);
        try (StubTcpServer server = new StubTcpServer(socket -> recordLines(socket, received))) {
            DeviceSession session = clientSession(server.port(), watchdog(60, 5), fireAndForget);
            session.start();
            awaitState(session, DeviceSessionState.UP, 2_000);
            session.send("DATA".getBytes(StandardCharsets.ISO_8859_1));
            awaitTrue(() -> received.contains("DATA"), 2_000);
            session.close();
        }
    }

    @Test
    void unsolicitedFrameIsHandedToInboundSink() throws Exception {
        try (StubTcpServer server = new StubTcpServer(StubTcpServer.sendThenSilent("STATUS\n"))) {
            DeviceSession session = clientSession(server.port(), watchdog(60, 5), null);
            session.start();
            awaitTrue(() -> !inboundFrames.isEmpty(), 2_000);
            assertThat(new String(inboundFrames.get(0), StandardCharsets.ISO_8859_1)).isEqualTo("STATUS");
            session.close();
        }
    }

    @Test
    void ackFramesAreNotTreatedAsInbound() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        try (StubTcpServer server = new StubTcpServer(socket -> ackResponder(socket, received))) {
            DeviceSession session = clientSession(server.port(), watchdog(60, 5), null);
            session.start();
            awaitState(session, DeviceSessionState.UP, 2_000);
            session.send("HELLO".getBytes(StandardCharsets.ISO_8859_1));
            assertThat(received).contains("HELLO");
            Thread.sleep(200);
            assertThat(inboundFrames).isEmpty(); // the ACK completed the send; it isn't an inbound message
            session.close();
        }
    }

    // ---- helpers ----

    private DeviceSession clientSession(int port, TcpSession.Heartbeat hb, TcpProtocol protocol) {
        TcpSession session = new TcpSession(TcpSession.Mode.PERSISTENT, TcpSession.Role.CLIENT,
                port, null, null, hb, null);
        DeviceSessionConfig cfg = new DeviceSessionConfig("dev-1", "127.0.0.1", protocol, session);
        return new DeviceSession(cfg, connectExecutor, scheduler, 500, 50, 200, 500, inboundFrames::add);
    }

    private static TcpSession.Heartbeat watchdog(int expectInboundSec, int missThreshold) {
        return new TcpSession.Heartbeat(null, null, null, null, expectInboundSec, missThreshold);
    }

    private static void awaitState(DeviceSession session, DeviceSessionState expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (expected.name().equals(session.status().state())) {
                return;
            }
            Thread.sleep(25);
        }
        assertThat(session.status().state()).isEqualTo(expected.name());
    }

    private static void awaitTrue(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /** Record each newline-terminated frame and ack it with {@code ACK\n}. */
    private static void ackResponder(Socket socket, List<String> received) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            OutputStream out = socket.getOutputStream();
            String line;
            while ((line = reader.readLine()) != null) {
                received.add(line);
                out.write("ACK\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }
        } catch (IOException ignored) {
            // connection ended
        }
    }

    /** Record each newline-terminated frame; never reply. */
    private static void recordLines(Socket socket, List<String> received) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String line;
            while ((line = reader.readLine()) != null) {
                received.add(line);
            }
        } catch (IOException ignored) {
            // connection ended
        }
    }

    private static ThreadFactory daemon(String prefix) {
        return r -> {
            Thread t = new Thread(r, "test-session-" + prefix);
            t.setDaemon(true);
            return t;
        };
    }
}
