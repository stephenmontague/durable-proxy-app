package com.dummyedge;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "edge")
public record EdgeProperties(Proxy proxy, int tcpListenPort, int ftpListenPort, String ftpRoot,
                             String ftpUser, String ftpPassword, String cycleCountFolder,
                             long confirmDelayMs, Tcp tcp) {

    public record Proxy(String httpBase, String pickConfirmPath, String tcpHost,
                        int putawayConfirmPort, String ftpHost, int ftpPort, String ftpUser,
                        String ftpPassword, String cycleCountConfirmFolder) {
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
