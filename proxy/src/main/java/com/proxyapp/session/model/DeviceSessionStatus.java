package com.proxyapp.session.model;

import java.util.List;

/**
 * Point-in-time health of one persistent device link, reported inside
 * {@link com.proxyapp.control.model.AppliedStatus}. The diagnostic fields carry the <i>why</i> of a
 * drop to the cloud, since the proxy's own logs sit on an unreachable edge machine.
 * {@code lastError} clears on UP but survives CONNECTING, so a reconnecting link still explains its
 * last drop; {@code recentEvents} is a bounded most-recent-last buffer, null in older reports.
 */
public record DeviceSessionStatus(String deviceId, String role, String state,
                                  String lastHeartbeatAt, int inflight,
                                  String lastError, String lastTransitionAt,
                                  List<SessionEvent> recentEvents) {
}
