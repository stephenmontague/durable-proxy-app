package com.proxyapp.session;

/**
 * Point-in-time health of one persistent device link, reported to the control workflow inside
 * {@link com.proxyapp.control.AppliedStatus} (so it rides the egress connection like everything
 * else) and rendered as a per-device lamp in the UI. Mirrored in the management UI's types.ts.
 *
 * @param deviceId        device this link serves
 * @param role            CLIENT or SERVER ({@code TcpSession.Role} name)
 * @param state           CONNECTING / UP / DOWN
 * @param lastHeartbeatAt ISO-8601 of the last inbound frame (proof of life), or null if none yet
 * @param inflight        outbound messages currently awaiting their ack on this link
 */
public record DeviceSessionStatus(String deviceId, String role, String state,
                                  String lastHeartbeatAt, int inflight) {
}
