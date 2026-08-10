package com.proxyapp.routing.model;

import java.util.List;

/**
 * One edge target (device/machine/network endpoint) and its routing bindings. Site-infrastructure
 * fields ({@code baseUrl}, {@code host}, FTP credentials) are set once at install time;
 * {@code bindings} is the ops-editable layer. {@code tcpProtocol} is the device default, overridable
 * per binding; null on either it or {@code tcpSession} means legacy framing / connect-per-message.
 */
public record EdgeConfig(String deviceId, String baseUrl, String host, Integer ftpPort,
                         String ftpUser, String ftpPassword, List<RouteBinding> bindings,
                         TcpProtocol tcpProtocol, TcpSession tcpSession) {

    /** A device with no TCP wire-protocol override and no persistent session — the common case. */
    public EdgeConfig(String deviceId, String baseUrl, String host, Integer ftpPort,
                      String ftpUser, String ftpPassword, List<RouteBinding> bindings) {
        this(deviceId, baseUrl, host, ftpPort, ftpUser, ftpPassword, bindings, null, null);
    }

    public List<RouteBinding> bindings() {
        return bindings == null ? List.of() : bindings;
    }
}
