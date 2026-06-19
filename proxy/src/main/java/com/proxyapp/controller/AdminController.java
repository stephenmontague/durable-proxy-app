package com.proxyapp.controller;
import com.proxyapp.ingress.FtpIngressListener;
import com.proxyapp.ingress.TcpSocketServer;

import com.proxyapp.config.ProxyProperties;
import com.proxyapp.routing.RoutingState;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Local observability for operators; exact mapping wins over the ingress catch-all. */
@RestController
public class AdminController {

    private final RoutingState routingState;
    private final TcpSocketServer tcpSocketServer;
    private final FtpIngressListener ftpIngressListener;
    private final ProxyProperties properties;

    public AdminController(RoutingState routingState, TcpSocketServer tcpSocketServer,
                           FtpIngressListener ftpIngressListener, ProxyProperties properties) {
        this.routingState = routingState;
        this.tcpSocketServer = tcpSocketServer;
        this.ftpIngressListener = ftpIngressListener;
        this.properties = properties;
    }

    @GetMapping("/admin/status")
    public Map<String, Object> status() {
        return Map.of(
                "profile", properties.profile(),
                "enabled", routingState.enabled(),
                "appliedVersion", routingState.appliedVersion(),
                "httpPaths", routingState.table().inboundHttpPaths(),
                "tcpPorts", tcpSocketServer.activePorts(),
                "ftpFolders", ftpIngressListener.activeFolders());
    }
}
