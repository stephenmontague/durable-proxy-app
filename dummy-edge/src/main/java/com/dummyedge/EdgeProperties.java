package com.dummyedge;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "edge")
public record EdgeProperties(Proxy proxy, int tcpListenPort, int ftpListenPort, String ftpRoot,
                             String ftpUser, String ftpPassword, String reportRequestFolder,
                             long confirmDelayMs, Tcp tcp, Boolean xml) {

    /** True when this device speaks XML instead of JSON (the {@code xml} Spring profile). */
    public boolean xmlConfirms() {
        return Boolean.TRUE.equals(xml);
    }

    public record Proxy(String httpBase, String commandResultPath, String tcpHost,
                        int configAckPort, String ftpHost, int ftpPort, String ftpUser,
                        String ftpPassword, String reportUploadFolder) {
    }

    /**
     * Optional TCP wire-protocol simulation (raw strings — YAML double-quoted escapes
     * like {@code "\x0B"} carry the control bytes). Absent = legacy EOF framing. Applies
     * to both the device's listen side and its confirm pushes. See the {@code framed}
     * Spring profile for an MLLP-style setup.
     */
    public record Tcp(String startDelimiter, String endDelimiter, String ackReply,
                      String expectedAck, Boolean awaitReply) {

        public boolean framed() {
            return endDelimiter != null && !endDelimiter.isEmpty();
        }
    }
}
