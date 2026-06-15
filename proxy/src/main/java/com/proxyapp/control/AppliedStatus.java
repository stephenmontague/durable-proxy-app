package com.proxyapp.control;

import com.proxyapp.session.DeviceSessionStatus;

import java.util.List;

/**
 * What the proxy actually has running, reported back to the control workflow after each
 * reconcile. Lets a cloud-side UI show desired vs applied state without ever reaching the
 * proxy's network — the report rides the same egress connection as everything else.
 *
 * @param version    the desired-state version the proxy has applied
 * @param enabled    whether the data plane is actually running
 * @param httpPaths  inbound HTTP channels currently routable
 * @param tcpPorts   inbound TCP ports currently listening
 * @param ftpFolders inbound FTP folders currently watched
 * @param startedAt  proxy process start time (ISO-8601, proxy clock)
 * @param reportedAt when this report was generated (ISO-8601, proxy clock)
 * @param supervised whether a supervisor will relaunch the process after a restart
 *                   command (PROXY_SUPERVISED env var, set by proxy-supervisor.sh or a
 *                   service unit). False means RESTART behaves like SHUTDOWN.
 * @param sessions   per-device persistent-link health (CONNECTING/UP/DOWN); empty when no device
 *                   uses a persistent TCP session
 */
public record AppliedStatus(long version, boolean enabled, List<String> httpPaths,
                            List<Integer> tcpPorts, List<String> ftpFolders,
                            String startedAt, String reportedAt, boolean supervised,
                            List<DeviceSessionStatus> sessions) {

    /** Pre-persistent-session call sites: no device sessions. */
    public AppliedStatus(long version, boolean enabled, List<String> httpPaths,
                         List<Integer> tcpPorts, List<String> ftpFolders,
                         String startedAt, String reportedAt, boolean supervised) {
        this(version, enabled, httpPaths, tcpPorts, ftpFolders, startedAt, reportedAt,
                supervised, List.of());
    }
}
