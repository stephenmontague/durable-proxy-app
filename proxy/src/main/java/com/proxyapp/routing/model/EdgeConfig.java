package com.proxyapp.routing.model;

import java.util.List;

/**
 * One edge target (device/machine/network endpoint) and its routing bindings.
 * Site-infrastructure fields ({@code baseUrl}, {@code host}, FTP credentials) are set once
 * at install time; {@code bindings} is the ops-editable layer.
 *
 * @param deviceId    unique id of this edge target within the install
 * @param baseUrl     HTTP base URL of the device (outbound HTTP), e.g. http://192.168.1.50:8082
 * @param host        host/IP for raw TCP and FTP (outbound)
 * @param ftpPort     device FTP port (outbound FTP), null if unused
 * @param ftpUser     device FTP user (outbound FTP)
 * @param ftpPassword device FTP password (outbound FTP)
 * @param bindings    per-message-type channel bindings
 * @param tcpProtocol device-default TCP wire protocol; null = legacy. Individual TCP
 *                    bindings may override via {@link RouteBinding#tcpProtocol()}.
 * @param tcpSession  optional persistent-TCP-session config (a heartbeated long-lived socket);
 *                    null or {@link TcpSession.Mode#PER_MESSAGE} = today's connect-per-message.
 */
public record EdgeConfig(String deviceId, String baseUrl, String host, Integer ftpPort,
                         String ftpUser, String ftpPassword, List<RouteBinding> bindings,
                         TcpProtocol tcpProtocol, TcpSession tcpSession) {

    public EdgeConfig(String deviceId, String baseUrl, String host, Integer ftpPort,
                      String ftpUser, String ftpPassword, List<RouteBinding> bindings) {
        this(deviceId, baseUrl, host, ftpPort, ftpUser, ftpPassword, bindings, null, null);
    }

    public EdgeConfig(String deviceId, String baseUrl, String host, Integer ftpPort,
                      String ftpUser, String ftpPassword, List<RouteBinding> bindings,
                      TcpProtocol tcpProtocol) {
        this(deviceId, baseUrl, host, ftpPort, ftpUser, ftpPassword, bindings, tcpProtocol, null);
    }

    public List<RouteBinding> bindings() {
        return bindings == null ? List.of() : bindings;
    }
}
