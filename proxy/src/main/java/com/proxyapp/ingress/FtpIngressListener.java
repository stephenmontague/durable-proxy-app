package com.proxyapp.ingress;

import com.proxyapp.config.ProxyProperties;
import com.proxyapp.routing.model.Transport;
import jakarta.annotation.PreDestroy;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.DefaultFtplet;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.ftplet.FtpSession;
import org.apache.ftpserver.ftplet.Ftplet;
import org.apache.ftpserver.ftplet.FtpletResult;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * FTP ingress: an embedded drop-server whose folders are the channels. The edge target
 * uploads into a per-type folder; on upload completion the file is handed to the gateway
 * and deleted only after Temporal accepted the enqueue (consume = ack). Files whose
 * enqueue fails stay in place and are re-swept on the next reconcile.
 *
 * <p>Runs only while at least one FTP folder is bound; the Reconciler stops it on disable.
 */
public class FtpIngressListener {

    private static final Logger log = LoggerFactory.getLogger(FtpIngressListener.class);

    private final InboundGateway gateway;
    private final ProxyProperties properties;
    private final Set<String> watchedFolders = new CopyOnWriteArraySet<>();
    private FtpServer server;

    public FtpIngressListener(InboundGateway gateway, ProxyProperties properties) {
        this.gateway = gateway;
        this.properties = properties;
    }

    public synchronized void reconcile(Set<String> desiredFolders) {
        watchedFolders.clear();
        watchedFolders.addAll(desiredFolders);
        try {
            if (desiredFolders.isEmpty()) {
                stopServer();
                return;
            }
            for (String folder : desiredFolders) {
                Files.createDirectories(rootDir().resolve(folder));
            }
            startServerIfNeeded();
            sweepExisting();
        } catch (Exception e) {
            log.error("FTP ingress reconcile failed", e);
        }
    }

    public Set<String> activeFolders() {
        return Set.copyOf(watchedFolders);
    }

    private Path rootDir() throws IOException {
        Path root = Path.of(properties.ingress().ftpRoot()).toAbsolutePath();
        Files.createDirectories(root);
        return root;
    }

    private void startServerIfNeeded() throws IOException, FtpException {
        if (server != null && !server.isStopped()) {
            return;
        }
        FtpServerFactory serverFactory = new FtpServerFactory();
        ListenerFactory listenerFactory = new ListenerFactory();
        listenerFactory.setPort(properties.ingress().ftpPort());
        serverFactory.addListener("default", listenerFactory.createListener());

        BaseUser user = new BaseUser();
        user.setName(properties.ingress().ftpUser());
        user.setPassword(properties.ingress().ftpPassword());
        user.setHomeDirectory(rootDir().toString());
        user.setAuthorities(List.of(new WritePermission()));
        serverFactory.getUserManager().save(user);

        serverFactory.getFtplets().put("ingress", ingressFtplet());
        server = serverFactory.createServer();
        server.start();
        log.info("FTP ingress listening on port {} (root {})",
                properties.ingress().ftpPort(), rootDir());
    }

    private void stopServer() {
        if (server != null && !server.isStopped()) {
            server.stop();
            log.info("FTP ingress stopped");
        }
        server = null;
    }

    private Ftplet ingressFtplet() {
        return new DefaultFtplet() {
            @Override
            public FtpletResult onUploadEnd(FtpSession session, FtpRequest request)
                    throws FtpException, IOException {
                processVirtualPath(virtualPath(session, request));
                return FtpletResult.DEFAULT;
            }

            @Override
            public FtpletResult onRenameEnd(FtpSession session, FtpRequest request)
                    throws FtpException, IOException {
                // Writers using the upload-temp-then-rename pattern land here.
                processVirtualPath(virtualPath(session, request));
                return FtpletResult.DEFAULT;
            }

            private String virtualPath(FtpSession session, FtpRequest request)
                    throws FtpException {
                return session.getFileSystemView().getFile(request.getArgument())
                        .getAbsolutePath();
            }
        };
    }

    private void processVirtualPath(String virtualPath) throws IOException {
        // virtualPath looks like /folder[/sub]/file relative to the FTP root
        String relative = virtualPath.startsWith("/") ? virtualPath.substring(1) : virtualPath;
        int lastSlash = relative.lastIndexOf('/');
        if (lastSlash <= 0) {
            return; // file at root, not a channel
        }
        String folder = relative.substring(0, lastSlash);
        String filename = relative.substring(lastSlash + 1);
        if (filename.startsWith(".") || !watchedFolders.contains(folder)) {
            return; // temp upload or unbound folder
        }
        processFile(rootDir().resolve(relative), folder, filename);
    }

    private void processFile(Path file, String folder, String filename) {
        try {
            byte[] raw = Files.readAllBytes(file);
            gateway.handle(Transport.FTP, folder, filename, raw);
            Files.deleteIfExists(file);
        } catch (IngressException e) {
            log.warn("FTP ingress kept {} ({}: {})", file, e.reason(), e.getMessage());
        } catch (Exception e) {
            log.error("FTP ingress failed to process {}", file, e);
        }
    }

    /** Store-and-forward: pick up files that arrived earlier or whose enqueue failed. */
    private void sweepExisting() throws IOException {
        for (String folder : watchedFolders) {
            Path dir = rootDir().resolve(folder);
            try (var files = Files.list(dir)) {
                files.filter(Files::isRegularFile)
                        .filter(f -> !f.getFileName().toString().startsWith("."))
                        .forEach(f -> processFile(f, folder, f.getFileName().toString()));
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        stopServer();
    }
}
