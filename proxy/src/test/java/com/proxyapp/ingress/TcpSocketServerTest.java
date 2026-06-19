package com.proxyapp.ingress;

import com.proxyapp.routing.model.TcpProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class TcpSocketServerTest {

    private final List<String> received = new CopyOnWriteArrayList<>();
    private TcpSocketServer server;
    private int port;

    private final InboundSink sink = (transport, channel, filename, raw) -> {
        String payload = new String(raw, StandardCharsets.UTF_8);
        if (payload.contains("REJECT")) {
            throw new IngressException(IngressException.Reason.UNKNOWN_CHANNEL, "nope");
        }
        received.add(payload);
        return new InboundGateway.EnqueueResult("ACT-" + payload, false);
    };

    @BeforeEach
    void setUp() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        server = new TcpSocketServer(sink, 3_000, 1_500);
    }

    @AfterEach
    void tearDown() {
        server.shutdown();
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(3_000);
        return socket;
    }

    private static String readReply(InputStream in, int expectedLength) throws IOException {
        return new String(in.readNBytes(expectedLength), StandardCharsets.ISO_8859_1);
    }

    @Test
    void legacyEofRoundTrip() throws IOException {
        server.reconcile(mapOf(port, null));
        try (Socket socket = connect()) {
            socket.getOutputStream().write("hello".getBytes(StandardCharsets.UTF_8));
            socket.shutdownOutput();
            String reply = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(reply).isEqualTo("ACK ACT-hello\n");
        }
        assertThat(received).containsExactly("hello");
    }

    @Test
    void framedMultipleMessagesOverOneConnection() throws IOException {
        TcpProtocol mllp = new TcpProtocol("<VT>", "<FS><CR>",
                "<VT>OK {activityId}<FS><CR>", "<VT>NO {reason}<FS><CR>", null, null);
        server.reconcile(mapOf(port, mllp));
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write("one\r".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            assertThat(readReply(in, "OK ACT-one\r".length()))
                    .isEqualTo("OK ACT-one\r");

            out.write("two\r".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            assertThat(readReply(in, "OK ACT-two\r".length()))
                    .isEqualTo("OK ACT-two\r");
        }
        assertThat(received).containsExactly("one", "two");
    }

    @Test
    void noiseBeforeStartDelimiterIsDiscarded() throws IOException {
        TcpProtocol framed = new TcpProtocol("<STX>", "<ETX>", "ok {activityId}", null, null, null);
        server.reconcile(mapOf(port, framed));
        try (Socket socket = connect()) {
            socket.getOutputStream().write("garbagepayload".getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            assertThat(readReply(socket.getInputStream(), "ok ACT-payload".length()))
                    .isEqualTo("ok ACT-payload");
        }
        assertThat(received).containsExactly("payload");
    }

    @Test
    void rejectedFrameNaksAndConnectionContinues() throws IOException {
        TcpProtocol framed = new TcpProtocol(null, "<LF>", "ACK {activityId}<LF>",
                "NAK {reason}<LF>", null, null);
        server.reconcile(mapOf(port, framed));
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write("REJECT-me\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            assertThat(readReply(in, "NAK UNKNOWN_CHANNEL\n".length()))
                    .isEqualTo("NAK UNKNOWN_CHANNEL\n");

            // same connection still works for the next frame
            out.write("good\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            assertThat(readReply(in, "ACK ACT-good\n".length())).isEqualTo("ACK ACT-good\n");
        }
        assertThat(received).containsExactly("good");
    }

    @Test
    void customAckTemplateAppliesInLegacyEofModeToo() throws IOException {
        // Only the replies customized — framing stays EOF
        TcpProtocol acksOnly = new TcpProtocol(null, null, "PONG {activityId}", "PANG {reason}", null, null);
        server.reconcile(mapOf(port, acksOnly));
        try (Socket socket = connect()) {
            socket.getOutputStream().write("ping".getBytes(StandardCharsets.UTF_8));
            socket.shutdownOutput();
            String reply = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertThat(reply).isEqualTo("PONG ACT-ping");
        }
    }

    @Test
    void samePortProtocolHotSwapKeepsListenerAndSnapshotsPerConnection() throws Exception {
        server.reconcile(mapOf(port, null));
        java.net.ServerSocket boundSocket = server.listenerSocket(port);
        try (Socket legacyConn = connect()) {
            // Engage the handler before swapping so this connection snapshots LEGACY.
            legacyConn.getOutputStream().write("old".getBytes(StandardCharsets.UTF_8));
            legacyConn.getOutputStream().flush();
            Thread.sleep(150);

            server.reconcile(mapOf(port, new TcpProtocol(null, "<LF>", "NEW {activityId}<LF>", null, null, null)));
            // the swap must not rebind the port — same ServerSocket instance
            assertThat(server.listenerSocket(port)).isSameAs(boundSocket);

            // held-open connection finishes on the OLD protocol
            legacyConn.shutdownOutput();
            String reply = new String(legacyConn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(reply).isEqualTo("ACK ACT-old\n");

            // a NEW connection speaks the new framed protocol
            try (Socket framedConn = connect()) {
                framedConn.getOutputStream().write("new\n".getBytes(StandardCharsets.UTF_8));
                framedConn.getOutputStream().flush();
                assertThat(readReply(framedConn.getInputStream(), "NEW ACT-new\n".length()))
                        .isEqualTo("NEW ACT-new\n");
            }
        }
        assertThat(received).containsExactly("old", "new");
    }

    private static Map<Integer, TcpProtocol> mapOf(int port, TcpProtocol protocol) {
        Map<Integer, TcpProtocol> map = new java.util.HashMap<>();
        map.put(port, protocol);
        return map;
    }
}
