package com.proxyapp.session.model;
import com.proxyapp.routing.model.EdgeConfig;

import com.proxyapp.routing.model.TcpProtocol;
import com.proxyapp.routing.model.TcpSession;

/**
 * The slim, runtime-facing view of a device's persistent-session config that
 * {@link TcpSessionManager} reconciles against — derived from an
 * {@link com.proxyapp.routing.model.EdgeConfig} whose {@link TcpSession} is PERSISTENT. Record equality
 * drives reconcile's "did this device's session change?" check (reopen on any change).
 *
 * @param deviceId device id
 * @param host     device host the CLIENT dials (from EdgeConfig.host)
 * @param protocol frame delimiters reused from the device/binding TcpProtocol; null = newline framing
 * @param session  the persistent-session settings (role, port, heartbeat, correlation)
 */
public record DeviceSessionConfig(String deviceId, String host, TcpProtocol protocol, TcpSession session) {
}
