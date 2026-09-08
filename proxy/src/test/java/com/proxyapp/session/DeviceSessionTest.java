package com.proxyapp.session;

import com.proxyapp.routing.model.TcpProtocol;
import com.proxyapp.routing.model.TcpSession;
import com.proxyapp.session.model.DeviceSessionConfig;
import com.proxyapp.session.model.DeviceSessionState;
import com.proxyapp.support.Await;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CLIENT-mode session behavior against a stub server: connect→UP, reconnect on drop, and UP↔DOWN
 * transitions from missed heartbeats (passive watchdog and active ping alike). Intervals are
 * config-driven in seconds, so a few tests take a couple of real seconds.
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
            // lastError clears once the link is back UP; the drop is still in the event history
            assertThat(session.status().lastError()).isNull();
            assertThat(session.status().recentEvents())
                    .anySatisfy(e -> assertThat(e.state()).isEqualTo("DOWN"));
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
            // the "why" rides in the status (not just the unreachable local log)
            assertThat(session.status().lastError()).contains("missed heartbeat");
            assertThat(session.status().recentEvents()).anySatisfy(e -> {
                assertThat(e.state()).isEqualTo("DOWN");
                assertThat(e.detail()).contains("missed heartbeat");
            });
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
            assertThat(session.status().lastError()).contains("missed heartbeat");
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

    @Test
    void serverRoleServesAHandedSocket() throws Exception {
        // SERVER sessions are passive: the manager's acceptor hands over an accepted socket.
        DeviceSession session = serverSession();
        session.start();
        try (ServerSocket relay = new ServerSocket(0); Socket device = new Socket()) {
            device.connect(new InetSocketAddress("127.0.0.1", relay.getLocalPort()), 2_000);
            session.serveAcceptedSocket(relay.accept());
            awaitState(session, DeviceSessionState.UP, 2_000);
            device.getOutputStream().write("STATUS\n".getBytes(StandardCharsets.ISO_8859_1));
            device.getOutputStream().flush();
            awaitTrue(() -> !inboundFrames.isEmpty(), 2_000);
            assertThat(new String(inboundFrames.get(0), StandardCharsets.ISO_8859_1)).isEqualTo("STATUS");
        }
        session.close();
    }

    @Test
    void sendFailsAsSoonAsTheLinkDropsInsteadOfWaitingOutTheAckTimeout() throws Exception {
        // Device never acks: without the wake, this send blocks for the full 30s.
        long ackTimeoutMs = 30_000;
        try (StubTcpServer server = new StubTcpServer(StubTcpServer::silent)) {
            DeviceSession session = clientSession(server.port(), watchdog(60, 5), null, ackTimeoutMs);
            session.start();
            awaitState(session, DeviceSessionState.UP, 2_000);

            AtomicReference<Throwable> thrown = new AtomicReference<>();
            AtomicLong elapsedMs = new AtomicLong();
            Thread sender = new Thread(() -> {
                long start = System.currentTimeMillis();
                try {
                    session.send("NEEDS-ACK".getBytes(StandardCharsets.ISO_8859_1));
                } catch (Throwable t) {
                    thrown.set(t);
                } finally {
                    elapsedMs.set(System.currentTimeMillis() - start);
                }
            }, "test-sender");
            sender.setDaemon(true);
            sender.start();

            // Let the send get parked in its ack wait, then drop the link out from under it.
            awaitTrue(() -> session.status().inflight() == 1, 2_000);
            for (Socket accepted : server.accepted) {
                closeQuietly(accepted);
            }

            sender.join(10_000);
            assertThat(sender.isAlive()).isFalse();
            assertThat(thrown.get())
                    .isInstanceOf(SessionSendException.class)
                    .hasMessageContaining("before its send was acked");
            assertThat(elapsedMs.get()).isLessThan(ackTimeoutMs / 2);
            session.close();
        }
    }

    @Test
    void closingASessionAlsoReleasesAParkedSend() throws Exception {
        long ackTimeoutMs = 30_000;
        try (StubTcpServer server = new StubTcpServer(StubTcpServer::silent)) {
            DeviceSession session = clientSession(server.port(), watchdog(60, 5), null, ackTimeoutMs);
            session.start();
            awaitState(session, DeviceSessionState.UP, 2_000);

            AtomicReference<Throwable> thrown = new AtomicReference<>();
            Thread sender = new Thread(() -> {
                try {
                    session.send("NEEDS-ACK".getBytes(StandardCharsets.ISO_8859_1));
                } catch (Throwable t) {
                    thrown.set(t);
                }
            }, "test-sender");
            sender.setDaemon(true);
            sender.start();

            awaitTrue(() -> session.status().inflight() == 1, 2_000);
            session.close();

            sender.join(10_000);
            assertThat(sender.isAlive()).isFalse();
            assertThat(thrown.get()).isInstanceOf(SessionSendException.class);
        }
    }

    // ---- helpers ----

    private DeviceSession clientSession(int port, TcpSession.Heartbeat hb, TcpProtocol protocol) {
        return clientSession(port, hb, protocol, 500);
    }

    private DeviceSession clientSession(int port, TcpSession.Heartbeat hb, TcpProtocol protocol,
                                        long sendAckTimeoutMs) {
        TcpSession session = new TcpSession(TcpSession.Mode.PERSISTENT, TcpSession.Role.CLIENT,
                port, null, null, hb, null);
        DeviceSessionConfig cfg = new DeviceSessionConfig("dev-1", "127.0.0.1", protocol, session);
        return new DeviceSession(cfg, connectExecutor, scheduler, 500, 50, 200,
                sendAckTimeoutMs, inboundFrames::add);
    }

    private DeviceSession serverSession() {
        // listenPort is config the manager's acceptor would use; this passive session is handed a socket.
        TcpSession session = new TcpSession(TcpSession.Mode.PERSISTENT, TcpSession.Role.SERVER,
                null, 9999, null, watchdog(60, 5), null);
        DeviceSessionConfig cfg = new DeviceSessionConfig("dev-1", null, null, session);
        return new DeviceSession(cfg, connectExecutor, scheduler, 500, 50, 200, 500, inboundFrames::add);
    }

    private static TcpSession.Heartbeat watchdog(int expectInboundSec, int missThreshold) {
        return new TcpSession.Heartbeat(null, null, null, null, expectInboundSec, missThreshold);
    }

    private static void awaitState(DeviceSession session, DeviceSessionState expected, long timeoutMs) {
        Await.until("device session to reach " + expected,
                () -> expected.name().equals(session.status().state()), timeoutMs);
    }

    private static void awaitTrue(BooleanSupplier condition, long timeoutMs) {
        Await.until(condition, timeoutMs);
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
