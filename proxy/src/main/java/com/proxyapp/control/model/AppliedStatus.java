package com.proxyapp.control.model;

import com.proxyapp.session.model.DeviceSessionStatus;

import java.util.List;

/**
 * What the proxy actually has running, reported after each reconcile so a cloud client can compare
 * desired against applied. Timestamps are ISO-8601 on the proxy clock. {@code supervised} = the
 * deployment set {@code PROXY_SUPERVISED}, declaring something will relaunch the process after a
 * restart exit; without it RESTART behaves like SHUTDOWN.
 */
public record AppliedStatus(long version, boolean enabled, List<String> httpPaths,
                            List<Integer> tcpPorts, List<String> ftpFolders,
                            String startedAt, String reportedAt, boolean supervised,
                            List<DeviceSessionStatus> sessions) {
}
