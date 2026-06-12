package com.proxyapp.control;

import com.proxyapp.config.ProxyProperties;
import com.proxyapp.ingress.FtpIngressListener;
import com.proxyapp.ingress.TcpSocketServer;
import com.proxyapp.routing.ConfigValidator;
import com.proxyapp.routing.MessageCatalog;
import com.proxyapp.routing.RouteTable;
import com.proxyapp.routing.RoutingState;
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
    private final WorkerFactory workerFactory;

    public Reconciler(ProxyProperties properties, MessageCatalog catalog,
                      RoutingState routingState, TcpSocketServer tcpSocketServer,
                      FtpIngressListener ftpIngressListener, WorkerFactory workerFactory) {
        this.properties = properties;
        this.catalog = catalog;
        this.routingState = routingState;
        this.tcpSocketServer = tcpSocketServer;
        this.ftpIngressListener = ftpIngressListener;
        this.workerFactory = workerFactory;
    }

    public synchronized void apply(ProxyControlState desired) {
        List<String> errors = ConfigValidator.validate(
                catalog, properties.tcpPortPool(), desired.getDevices());
        if (!errors.isEmpty()) {
            // The control workflow validates before accepting, so this only fires when the
            // local catalog/pool disagrees with what the workflow was seeded with.
            log.error("refusing to apply control state v{}: {}", desired.getVersion(), errors);
            return;
        }

        RouteTable table = new RouteTable(catalog, desired.getDevices());
        routingState.update(table, desired.isEnabled(), desired.getVersion());

        if (desired.isEnabled()) {
            tcpSocketServer.reconcile(table.inboundTcpProtocols());
            ftpIngressListener.reconcile(table.inboundFtpFolders());
            setDataWorkerSuspended(false);
        } else {
            tcpSocketServer.reconcile(Map.of());
            ftpIngressListener.reconcile(Set.of());
            setDataWorkerSuspended(true);
        }
        log.info("applied control state v{}: enabled={}, devices={}, tcpPorts={}, ftpFolders={}, httpPaths={}",
                desired.getVersion(), desired.isEnabled(), desired.getDevices().size(),
                table.inboundTcpPorts(), table.inboundFtpFolders(), table.inboundHttpPaths());
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
