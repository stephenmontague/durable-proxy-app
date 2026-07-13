package com.proxyapp.control;
import com.proxyapp.control.model.AppliedStatus;
import com.proxyapp.ingress.FtpIngressListener;
import com.proxyapp.ingress.TcpSocketServer;
import com.proxyapp.routing.RoutingState;
import com.proxyapp.session.TcpSessionManager;
import com.proxyapp.session.model.DeviceSessionStatus;
import com.proxyapp.temporal.workflow.ProxyControlWorkflow;

import io.temporal.client.WorkflowClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Reports the proxy's applied state to the control workflow over the egress connection. Routine
 * post-reconcile applied state is captured for free as the reconcile activity's return value; this
 * reporter exists to push <b>link-health transitions</b> (UP/DOWN/CONNECTING) that happen between
 * reconciles. A local timer diffs the in-memory session signature — this is NOT a Temporal poll, so
 * it costs zero Actions unless something actually changed — and on a change calls the
 * {@code reportApplied} Update. The Update returns the current desired version; if the proxy is
 * behind it self-heals with a {@code requestReconcile} signal.
 */
public class AppliedStatusReporter {

    private static final Logger log = LoggerFactory.getLogger(AppliedStatusReporter.class);
    private static final long REPORT_INTERVAL_MS = 2_000;
    private static final boolean SUPERVISED =
            Boolean.parseBoolean(System.getenv().getOrDefault("PROXY_SUPERVISED", "false"));

    private final WorkflowClient workflowClient;
    private final RoutingState routingState;
    private final TcpSocketServer tcpSocketServer;
    private final FtpIngressListener ftpIngressListener;
    private final TcpSessionManager tcpSessionManager;
    private final String startedAt = Instant.now().toString();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "applied-status-reporter");
                t.setDaemon(true);
                return t;
            });

    // Dedupe baseline — only report when the applied signature actually changes.
    private long lastVersion = Long.MIN_VALUE;
    private Boolean lastEnabled;
    private String lastSessionSig = "";

    public AppliedStatusReporter(WorkflowClient workflowClient, RoutingState routingState,
                                 TcpSocketServer tcpSocketServer, FtpIngressListener ftpIngressListener,
                                 TcpSessionManager tcpSessionManager) {
        this.workflowClient = workflowClient;
        this.routingState = routingState;
        this.tcpSocketServer = tcpSocketServer;
        this.ftpIngressListener = ftpIngressListener;
        this.tcpSessionManager = tcpSessionManager;
    }

    @PostConstruct
    public void start() {
        scheduler.scheduleWithFixedDelay(this::reportIfChanged,
                REPORT_INTERVAL_MS, REPORT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    /** The proxy's current applied state — used by the reconcile activity as its return value. */
    public AppliedStatus snapshot() {
        return new AppliedStatus(
                routingState.appliedVersion(),
                routingState.enabled(),
                routingState.table().inboundHttpPaths().stream().sorted().toList(),
                tcpSocketServer.activePorts().stream().sorted().toList(),
                ftpIngressListener.activeFolders().stream().sorted().toList(),
                startedAt, Instant.now().toString(), SUPERVISED,
                tcpSessionManager.statuses());
    }

    /**
     * Adopt {@code applied} as the reported baseline without sending anything — the reconcile
     * activity already returns it to the workflow, so the timer must not re-report the same state.
     */
    public synchronized void syncBaseline(AppliedStatus applied) {
        lastVersion = applied.version();
        lastEnabled = applied.enabled();
        lastSessionSig = sessionSignature(applied.sessions());
    }

    private void reportIfChanged() {
        AppliedStatus status = snapshot();
        if (status.version() < 0) {
            return; // nothing applied yet — stay silent until the first reconcile
        }
        String sessionSig = sessionSignature(status.sessions());
        synchronized (this) {
            if (status.version() == lastVersion
                    && lastEnabled != null && lastEnabled == status.enabled()
                    && sessionSig.equals(lastSessionSig)) {
                return; // unchanged — no Action
            }
        }
        try {
            // The Update returns desired version; the network call stays outside the lock so a slow
            // workflow can't stall the reconcile activity's syncBaseline().
            long desiredVersion = control().reportApplied(status);
            synchronized (this) {
                lastVersion = status.version();
                lastEnabled = status.enabled();
                lastSessionSig = sessionSig;
            }
            log.info("reported applied state v{} (enabled={}, sessions=[{}]) to control workflow",
                    status.version(), status.enabled(), sessionSig);
            if (desiredVersion > routingState.appliedVersion()) {
                log.info("applied v{} behind desired v{}; requesting reconcile to self-heal",
                        routingState.appliedVersion(), desiredVersion);
                control().requestReconcile();
            }
        } catch (Exception e) {
            // Workflow not started yet / mid-continue-as-new / transient — retry next tick.
            log.debug("applied-state report skipped: {}", e.toString());
        }
    }

    private ProxyControlWorkflow control() {
        return workflowClient.newWorkflowStub(ProxyControlWorkflow.class, ProxyControlWorkflow.WORKFLOW_ID);
    }

    /** deviceId:state pairs only — flips on link transitions, not on every heartbeat. */
    private static String sessionSignature(List<DeviceSessionStatus> sessions) {
        return String.join(",", sessions.stream()
                .map(s -> s.deviceId() + ":" + s.state())
                .toList());
    }
}
