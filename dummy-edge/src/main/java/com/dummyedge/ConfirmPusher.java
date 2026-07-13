package com.dummyedge;

import jakarta.annotation.PreDestroy;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pushes confirms back to the proxy over the same transport the request arrived on, after
 * a small simulated device delay. Like a real device, it retries a few times if the proxy
 * nacks or is unreachable.
 */
@Service
public class ConfirmPusher {

    private static final Logger log = LoggerFactory.getLogger(ConfirmPusher.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final long RETRY_DELAY_MS = 2_000;

    private final EdgeProperties properties;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "edge-confirm-pusher");
        t.setDaemon(true);
        return t;
    });

    public ConfirmPusher(EdgeProperties properties) {
        this.properties = properties;
    }

    public void pushHttpCommandResult(String payload) {
        String path = properties.proxy().commandResultPath();
        schedule("COMMAND_RESULT", payload, p -> sendHttpTo(path, p), 1);
    }

    /** Echo an inbound cloud→edge delivery back to the proxy on the SAME channel it arrived on. */
    public void pushHttpEcho(String requestPath, String payload) {
        schedule("ECHO", payload, p -> sendHttpTo(requestPath, p), 1);
    }

    public void pushTcpConfigAck(String payload) {
        schedule("CONFIG_ACK", payload, this::sendTcp, 1);
    }

    public void pushFtpReportUpload(String filename, String payload) {
        schedule("REPORT_UPLOAD", payload, p -> sendFtp(filename, p), 1);
    }

    private interface Sender {
        void send(String payload) throws Exception;
    }

    private void schedule(String what, String payload, Sender sender, int attempt) {
        long delay = attempt == 1 ? properties.confirmDelayMs() : RETRY_DELAY_MS;
        executor.schedule(() -> {
            try {
                sender.send(payload);
                log.info("pushed {} to proxy (attempt {})", what, attempt);
            } catch (Exception e) {
                if (attempt < MAX_ATTEMPTS) {
                    log.warn("push of {} failed (attempt {}): {} — retrying", what, attempt, e.getMessage());
                    schedule(what, payload, sender, attempt + 1);
                } else {
                    log.error("giving up pushing {} after {} attempts", what, MAX_ATTEMPTS, e);
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void sendHttpTo(String targetPath, String payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.proxy().httpBase() + targetPath))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("proxy returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }
    }

    private void sendTcp(String payload) throws Exception {
        var proxy = properties.proxy();
        var tcp = properties.tcp();
        boolean framed = tcp != null && tcp.framed();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(proxy.tcpHost(), proxy.configAckPort()), 5_000);
            socket.setSoTimeout(10_000);
            var out = socket.getOutputStream();
            if (!framed) {
                out.write(payload.getBytes(StandardCharsets.UTF_8));
                out.flush();
                socket.shutdownOutput();
                String reply = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!reply.startsWith("ACK")) {
                    throw new IllegalStateException("proxy nacked: " + reply.trim());
                }
                return;
            }
            // Framed: wrap the payload, keep the socket open, match the ack anywhere in
            // the (possibly framed) reply — mirrors how a real MLLP-style device behaves.
            if (tcp.startDelimiter() != null && !tcp.startDelimiter().isEmpty()) {
                out.write(tcp.startDelimiter().getBytes(StandardCharsets.ISO_8859_1));
            }
            out.write(payload.getBytes(StandardCharsets.UTF_8));
            out.write(tcp.endDelimiter().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            if (Boolean.FALSE.equals(tcp.awaitReply())) {
                return;
            }
            String expected = tcp.expectedAck() == null || tcp.expectedAck().isEmpty()
                    ? "ACK" : tcp.expectedAck();
            StringBuilder acc = new StringBuilder();
            int b;
            while ((b = socket.getInputStream().read()) >= 0) {
                acc.append((char) (b & 0xFF));
                if (acc.indexOf(expected) >= 0) {
                    return;
                }
            }
            throw new IllegalStateException("proxy closed without expected ack: " + acc);
        }
    }

    private void sendFtp(String filename, String payload) throws Exception {
        var proxy = properties.proxy();
        FTPClient client = new FTPClient();
        client.setConnectTimeout(5_000);
        try {
            client.connect(proxy.ftpHost(), proxy.ftpPort());
            if (!client.login(proxy.ftpUser(), proxy.ftpPassword())) {
                throw new IllegalStateException("FTP login to proxy failed");
            }
            client.enterLocalPassiveMode();
            client.setFileType(FTP.BINARY_FILE_TYPE);
            client.makeDirectory(proxy.reportUploadFolder());
            String path = proxy.reportUploadFolder() + "/" + filename;
            if (!client.storeFile(path, new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)))) {
                throw new IllegalStateException("FTP store to proxy failed: " + client.getReplyString());
            }
        } finally {
            if (client.isConnected()) {
                try {
                    client.logout();
                    client.disconnect();
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
