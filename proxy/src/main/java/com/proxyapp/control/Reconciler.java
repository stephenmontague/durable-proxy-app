package com.proxyapp.control;
import com.proxyapp.control.model.CatalogEntryDto;
import com.proxyapp.control.model.ProxyControlState;

import com.proxyapp.config.ProxyProperties;
import com.proxyapp.ingress.FtpIngressListener;
import com.proxyapp.ingress.TcpSocketServer;
import com.proxyapp.routing.ConfigValidator;
import com.proxyapp.routing.model.EdgeConfig;
import com.proxyapp.routing.MessageCatalog;
import com.proxyapp.routing.RouteTable;
import com.proxyapp.routing.RoutingState;
import com.proxyapp.session.model.DeviceSessionConfig;
import com.proxyapp.session.TcpSessionManager;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Diffs desired control state against what is running and hot-applies it — starts/stops
 * ingress listeners, swaps the route table, and pauses/resumes outbound processing —
 * with no restart.
 *
 * <p>"Off" is a soft disable: ingress listeners stop and the data worker suspends polling,
 * but the control worker (and the egress gRPC connection) stays alive so the cloud can
 * turn the install back on.
 */
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    private final ProxyProperties properties;
    private final MessageCatalog catalog;
    private final RoutingState routingState;
    private final TcpSocketServer tcpSocketServer;
    private final FtpIngressListener ftpIngressListener;
    private final TcpSessionManager tcpSessionManager;
    private final WorkerFactory workerFactory;

    public Reconciler(ProxyProperties properties, MessageCatalog catalog,
                      RoutingState routingState, TcpSocketServer tcpSocketServer,
                      FtpIngressListener ftpIngressListener, TcpSessionManager tcpSessionManager,
                      WorkerFactory workerFactory) {
        this.properties = properties;
        this.catalog = catalog;
        this.routingState = routingState;
        this.tcpSocketServer = tcpSocketServer;
        this.ftpIngressListener = ftpIngressListener;
        this.tcpSessionManager = tcpSessionManager;
        this.workerFactory = workerFactory;
    }

    public synchronized void apply(ProxyControlState desired) {
        // Never regress: a stale push (older version than what's live) is dropped. The awaited
        // workflow loop already serializes reconciles, so this is a defensive guard.
        if (desired.getVersion() < routingState.appliedVersion()) {
            log.debug("ignoring stale control state v{} (already applied v{})",
                    desired.getVersion(), routingState.appliedVersion());
            return;
        }

        MessageCatalog effectiveCatalog;
        try {
            effectiveCatalog = catalogFor(desired);
        } catch (RuntimeException e) {
            // Malformed catalog (e.g. duplicate types) — the workflow validates first, so this
            // is a belt-and-braces guard. Keep the last good config live rather than crash.
            log.error("refusing to apply control state v{}: invalid catalog: {}",
                    desired.getVersion(), e.getMessage());
            return;
        }

        List<String> errors = ConfigValidator.validate(
                effectiveCatalog, properties.tcpPortPool(), desired.getDevices());
        if (!errors.isEmpty()) {
            // The control workflow validates before accepting, so this only fires when the
            // local pool disagrees with what the workflow was seeded with.
            log.error("refusing to apply control state v{}: {}", desired.getVersion(), errors);
            return;
        }

        RouteTable table = new RouteTable(effectiveCatalog, desired.getDevices());
        routingState.update(table, desired.isEnabled(), desired.getVersion());

        if (desired.isEnabled()) {
            tcpSocketServer.reconcile(table.inboundTcpProtocols());
            ftpIngressListener.reconcile(table.inboundFtpFolders());
            tcpSessionManager.reconcile(persistentSessions(desired.getDevices()));
            setDataWorkerSuspended(false);
        } else {
            tcpSocketServer.reconcile(Map.of());
            ftpIngressListener.reconcile(Set.of());
            tcpSessionManager.reconcile(List.of());
            setDataWorkerSuspended(true);
        }
        log.info("applied control state v{}: enabled={}, devices={}, tcpPorts={}, ftpFolders={}, httpPaths={}",
                desired.getVersion(), desired.isEnabled(), desired.getDevices().size(),
                table.inboundTcpPorts(), table.inboundFtpFolders(), table.inboundHttpPaths());
    }

    /** Devices whose session is PERSISTENT, as the slim configs the session manager reconciles. */
    private List<DeviceSessionConfig> persistentSessions(List<EdgeConfig> devices) {
        return devices.stream()
                .filter(d -> d.tcpSession() != null && d.tcpSession().isPersistent())
                .map(d -> new DeviceSessionConfig(d.deviceId(), d.host(), d.tcpProtocol(), d.tcpSession()))
                .toList();
    }

    /**
     * The catalog to apply this reconcile: the operator-edited one held in control state, or —
     * for a workflow that predates Part 3 ({@code catalogEntries == null}) — the boot profile
     * catalog this proxy was built with. That fallback is what keeps upgrades non-breaking.
     */
    private MessageCatalog catalogFor(ProxyControlState desired) {
        List<CatalogEntryDto> entries = desired.getCatalogEntries();
        if (entries == null || entries.isEmpty()) {
            return catalog;
        }
        return new MessageCatalog(entries.stream()
                .map(CatalogEntryDto::toCatalogEntry)
                .toList());
    }

    private void setDataWorkerSuspended(boolean suspend) {
        Worker worker;
        try {
            worker = workerFactory.getWorker(properties.taskQueue());
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("data worker for task queue '{}' not available yet", properties.taskQueue());
            return;
        }
        if (suspend && !worker.isSuspended()) {
            worker.suspendPolling();
            log.info("outbound processing paused (data worker polling suspended)");
        } else if (!suspend && worker.isSuspended()) {
            worker.resumePolling();
            log.info("outbound processing resumed");
        }
    }
}
