package com.proxyapp.control.model;

import com.proxyapp.session.model.DeviceSessionStatus;

import java.util.List;

/**
 * What the proxy actually has running, reported back to the control workflow after each
 * reconcile. Lets a cloud-side client compare desired against applied state without ever reaching
 * the proxy's network — the report rides the same egress connection as everything else, which is
 * what makes an egress-only proxy observable at all.
 *
 * @param version    the desired-state version the proxy has applied
 * @param enabled    whether the data plane is actually running
 * @param httpPaths  inbound HTTP channels currently routable
 * @param tcpPorts   inbound TCP ports currently listening
 * @param ftpFolders inbound FTP folders currently watched
 * @param startedAt  proxy process start time (ISO-8601, proxy clock)
 * @param reportedAt when this report was generated (ISO-8601, proxy clock)
 * @param supervised whether the deployment declared (via the {@code PROXY_SUPERVISED} env var)
 *                   that something will relaunch the process after a restart exit. False means a
 *                   RESTART command behaves like SHUTDOWN — worth warning an operator about
 *                   before they issue one.
 * @param sessions   per-device persistent-link health (CONNECTING/UP/DOWN); empty when no device
 *                   uses a persistent TCP session
 */
public record AppliedStatus(long version, boolean enabled, List<String> httpPaths,
                            List<Integer> tcpPorts, List<String> ftpFolders,
                            String startedAt, String reportedAt, boolean supervised,
                            List<DeviceSessionStatus> sessions) {
}
