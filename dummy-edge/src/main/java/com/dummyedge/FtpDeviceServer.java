package com.dummyedge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.DefaultFtplet;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.ftplet.FtpSession;
import org.apache.ftpserver.ftplet.FtpletResult;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * FTP channel of the device: the proxy uploads REPORT_REQUEST files into the watched
 * folder; the device reads them and pushes the paired REPORT_UPLOAD into the
 * proxy's FTP ingress folder.
 */
@Component
public class FtpDeviceServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FtpDeviceServer.class);

    private final EdgeProperties properties;
    private final ReceivedStore receivedStore;
    private final ConfirmPusher confirmPusher;
    private final ObjectMapper mapper = new ObjectMapper();
    private FtpServer server;

    public FtpDeviceServer(EdgeProperties properties, ReceivedStore receivedStore,
                           ConfirmPusher confirmPusher) {
        this.properties = properties;
        this.receivedStore = receivedStore;
        this.confirmPusher = confirmPusher;
    }

    @Override
    public void start() {
        try {
            Path root = Path.of(properties.ftpRoot()).toAbsolutePath();
            Files.createDirectories(root.resolve(properties.reportRequestFolder()));

            FtpServerFactory serverFactory = new FtpServerFactory();
            ListenerFactory listenerFactory = new ListenerFactory();
            listenerFactory.setPort(properties.ftpListenPort());
            serverFactory.addListener("default", listenerFactory.createListener());

            BaseUser user = new BaseUser();
            user.setName(properties.ftpUser());
            user.setPassword(properties.ftpPassword());
            user.setHomeDirectory(root.toString());
            user.setAuthorities(List.of(new WritePermission()));
            serverFactory.getUserManager().save(user);

            serverFactory.getFtplets().put("device", new DefaultFtplet() {
                @Override
                public FtpletResult onUploadEnd(FtpSession session, FtpRequest request)
                        throws FtpException, IOException {
                    process(session, request);
                    return FtpletResult.DEFAULT;
                }

                @Override
                public FtpletResult onRenameEnd(FtpSession session, FtpRequest request)
                        throws FtpException, IOException {
                    process(session, request);
                    return FtpletResult.DEFAULT;
                }
            });

            server = serverFactory.createServer();
            server.start();
            log.info("device FTP channel listening on port {} (root {})",
                    properties.ftpListenPort(), root);
        } catch (Exception e) {
            throw new IllegalStateException("cannot start device FTP server", e);
        }
    }

    private void process(FtpSession session, FtpRequest request) throws FtpException {
        String virtualPath = session.getFileSystemView().getFile(request.getArgument()).getAbsolutePath();
        String relative = virtualPath.startsWith("/") ? virtualPath.substring(1) : virtualPath;
        int lastSlash = relative.lastIndexOf('/');
        if (lastSlash <= 0) {
            return;
        }
        String folder = relative.substring(0, lastSlash);
        String filename = relative.substring(lastSlash + 1);
        if (filename.startsWith(".") || !folder.equals(properties.reportRequestFolder())) {
            return;
        }
        try {
            Path file = Path.of(properties.ftpRoot()).toAbsolutePath().resolve(relative);
            String payload = Files.readString(file, StandardCharsets.UTF_8);
            log.info("device received report request via FTP {}: {}", filename, payload.trim());
            receivedStore.add("FTP", folder, payload);
            Files.deleteIfExists(file);

            JsonNode body = mapper.readTree(payload);
            ObjectNode confirm = mapper.createObjectNode();
            confirm.set("reportId", body.get("reportId"));
            confirm.set("kind", body.get("kind"));
            confirm.put("rows", 42);
            confirm.put("status", "UPLOADED");
            confirmPusher.pushFtpReportUpload(
                    "REPORT_UPLOAD-" + body.get("reportId").asText() + ".json",
                    confirm.toString());
        } catch (IOException e) {
            log.error("device failed to process FTP drop {}", relative, e);
        }
    }

    @Override
    public void stop() {
        if (server != null && !server.isStopped()) {
            server.stop();
        }
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isStopped();
    }
}
