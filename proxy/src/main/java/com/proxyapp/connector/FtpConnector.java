package com.proxyapp.connector;
import com.proxyapp.connector.model.ChannelTarget;

import com.proxyapp.routing.model.Transport;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Uploads the payload as a file into the device's folder. FTP is inherently
 * store-and-forward; the deterministic filename makes activity retries overwrite rather
 * than duplicate.
 */
public class FtpConnector implements Connector {

    @Override
    public Transport transport() {
        return Transport.FTP;
    }

    @Override
    public void send(ChannelTarget target, byte[] payload) {
        ChannelTarget.FtpTarget ftp = (ChannelTarget.FtpTarget) target;
        FTPClient client = new FTPClient();
        client.setConnectTimeout(5_000);
        try {
            client.connect(ftp.host(), ftp.port());
            if (!client.login(ftp.user(), ftp.password())) {
                throw new ConnectorSendException("FTP login to " + ftp.host() + " failed");
            }
            client.enterLocalPassiveMode();
            client.setFileType(FTP.BINARY_FILE_TYPE);
            ensureFolder(client, ftp.folder());
            // Upload under a temp name, then rename: the receiver never sees partial files.
            String tempName = ftp.folder() + "/." + ftp.filename() + ".part";
            String finalName = ftp.folder() + "/" + ftp.filename();
            if (!client.storeFile(tempName, new ByteArrayInputStream(payload))) {
                throw new ConnectorSendException("FTP store to " + ftp.host() + "/" + tempName
                        + " failed: " + client.getReplyString());
            }
            client.deleteFile(finalName);
            if (!client.rename(tempName, finalName)) {
                throw new ConnectorSendException("FTP rename to " + finalName
                        + " failed: " + client.getReplyString());
            }
        } catch (IOException e) {
            throw new ConnectorSendException("FTP send to " + ftp.host() + ":" + ftp.port() + " failed", e);
        } finally {
            try {
                if (client.isConnected()) {
                    client.logout();
                    client.disconnect();
                }
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        }
    }

    private static void ensureFolder(FTPClient client, String folder) throws IOException {
        for (String part : folder.split("/")) {
            if (!part.isBlank()) {
                client.makeDirectory(part);
                if (!client.changeWorkingDirectory(part)) {
                    throw new ConnectorSendException("FTP cannot enter folder segment '" + part + "'");
                }
            }
        }
        client.changeWorkingDirectory("/");
    }
}
