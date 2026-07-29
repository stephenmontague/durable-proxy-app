package com.proxyapp.session.model;
import com.proxyapp.routing.model.TcpSession;

import java.util.List;

/**
 * Point-in-time health of one persistent device link, reported to the control workflow inside
 * {@link com.proxyapp.control.model.AppliedStatus} (so it rides the egress connection like everything
 * else) and rendered as a per-device lamp in the UI. Mirrored in the management UI's types.ts.
 *
 * <p>The link's local logs sit on a firewalled edge machine no operator can reach, so the diagnostic
 * fields below ({@code lastError}, {@code lastTransitionAt}, {@code recentEvents}) carry the
 * <i>why</i> back over the same egress connection — a down link can be diagnosed from the cloud
 * without touching the proxy's logs.
 *
 * @param deviceId         device this link serves
 * @param role             CLIENT or SERVER ({@code TcpSession.Role} name)
 * @param state            CONNECTING / UP / DOWN
 * @param lastHeartbeatAt  ISO-8601 of the last inbound frame (proof of life), or null if none yet
 * @param inflight         outbound messages currently awaiting their ack on this link
 * @param lastError        reason for the most recent DOWN (e.g. "connection refused"); cleared to
 *                         null once the link comes back UP. Preserved across CONNECTING so a
 *                         reconnecting link still explains its last drop. Null if never faulted.
 * @param lastTransitionAt ISO-8601 of the most recent state transition, or null if none yet
 * @param recentEvents     bounded ring buffer (most-recent-last) of recent transitions; may be null
 *                         when deserialized from an older report
 */
public record DeviceSessionStatus(String deviceId, String role, String state,
                                  String lastHeartbeatAt, int inflight,
                                  String lastError, String lastTransitionAt,
                                  List<SessionEvent> recentEvents) {
}
