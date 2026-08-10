package com.proxyapp.session.model;

import com.proxyapp.routing.model.TcpProtocol;
import com.proxyapp.routing.model.TcpSession;

/**
 * Runtime-facing view of a device's persistent-session config, derived from an EdgeConfig whose
 * {@link TcpSession} is PERSISTENT. Record equality drives reconcile's "did this session change?"
 * check, which reopens the socket on any change. Null {@code protocol} = newline framing.
 */
public record DeviceSessionConfig(String deviceId, String host, TcpProtocol protocol, TcpSession session) {
}
