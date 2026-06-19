package com.proxyapp.connector;
import com.proxyapp.connector.model.ChannelTarget;

import com.proxyapp.routing.model.TcpProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TcpConnectorTest {

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final TcpConnector connector = new TcpConnector(2_000, 1_000);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private interface DeviceHandler {
        void handle(Socket socket) throws IOException;
    }

    /** One-shot stub device: accepts a single connection and runs the handler. */
    private int startDevice(DeviceHandler handler) throws IOException {
        ServerSocket serverSocket = new ServerSocket(0);
        executor.execute(() -> {
            try (serverSocket; Socket socket = serverSocket.accept()) {
                socket.setSoTimeout(3_000);
                handler.handle(socket);
            } catch (IOException ignored) {
                // test sockets close abruptly by design
            }
        });
        return serverSocket.getLocalPort();
    }

    @Test
    void legacyPathStillAcksOnEof() throws IOException {
        CompletableFuture<byte[]> seen = new CompletableFuture<>();
        int port = startDevice(socket -> {
            seen.complete(socket.getInputStream().readAllBytes());
            socket.getOutputStream().write("ACK\n".getBytes(StandardCharsets.UTF_8));
        });

        connector.send(new ChannelTarget.TcpTarget("127.0.0.1", port), "hello".getBytes());
        assertThat(seen.join()).isEqualTo("hello".getBytes());
    }

    @Test
    void framedSendMatchesAckInsideFramingEvenWhenDeviceNeverCloses() throws Exception {
        TcpProtocol mllp = new TcpProtocol("<VT>", "<FS><CR>", null, null, "ACK", null);
        CompletableFuture<byte[]> seen = new CompletableFuture<>();
        int port = startDevice(socket -> {
            // frame = VT + "data" + FS CR = 7 bytes
            seen.complete(socket.getInputStream().readNBytes(7));
            // framed ack, connection HELD OPEN — contains-match must exit early
            socket.getOutputStream().write(new byte[]{0x0B, 'A', 'C', 'K', 0x1C, 0x0D});
            socket.getOutputStream().flush();
            try {
                Thread.sleep(2_500); // longer than the connector's read timeout
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        connector.send(new ChannelTarget.TcpTarget("127.0.0.1", port, mllp), "data".getBytes());
        assertThat(seen.get(3, TimeUnit.SECONDS))
                .isEqualTo(new byte[]{0x0B, 'd', 'a', 't', 'a', 0x1C, 0x0D});
    }

    @Test
    void customExpectedAckIsHonored() throws IOException {
        TcpProtocol pingPong = new TcpProtocol(null, "<LF>", null, null, "PONG", null);
        int port = startDevice(socket -> {
            socket.getInputStream().readNBytes("ping\n".length());
            socket.getOutputStream().write("PONG\n".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        });

        connector.send(new ChannelTarget.TcpTarget("127.0.0.1", port, pingPong), "ping".getBytes());
    }

    @Test
    void wrongReplyThenCloseFailsTheSend() throws IOException {
        TcpProtocol proto = new TcpProtocol(null, "<LF>", null, null, "PONG", null);
        int port = startDevice(socket -> {
            socket.getInputStream().readNBytes("x\n".length());
            socket.getOutputStream().write("NEIN\n".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        });

        assertThatThrownBy(() ->
                connector.send(new ChannelTarget.TcpTarget("127.0.0.1", port, proto), "x".getBytes()))
                .isInstanceOf(ConnectorSendException.class)
                .hasMessageContaining("closed without the expected ack")
                .hasMessageContaining("NEIN");
    }

    @Test
    void silentDeviceTimesOutAndFailsTheSend() throws IOException {
        TcpProtocol proto = new TcpProtocol(null, "<LF>", null, null, "ACK", null);
        int port = startDevice(socket -> {
            socket.getInputStream().readNBytes("x\n".length());
            try {
                Thread.sleep(2_500); // never reply; connector read timeout is 1s
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        assertThatThrownBy(() ->
                connector.send(new ChannelTarget.TcpTarget("127.0.0.1", port, proto), "x".getBytes()))
                .isInstanceOf(ConnectorSendException.class)
                .hasMessageContaining("no expected ack within 1000ms");
    }

    @Test
    void fireAndForgetReturnsWithoutAnyReply() throws Exception {
        TcpProtocol fnf = new TcpProtocol("<STX>", "<ETX>", null, null, null, false);
        CompletableFuture<byte[]> seen = new CompletableFuture<>();
        int port = startDevice(socket ->
                seen.complete(socket.getInputStream().readAllBytes())); // no reply ever

        connector.send(new ChannelTarget.TcpTarget("127.0.0.1", port, fnf), "label".getBytes());
        assertThat(seen.get(3, TimeUnit.SECONDS))
                .isEqualTo(new byte[]{0x02, 'l', 'a', 'b', 'e', 'l', 0x03});
    }
}
