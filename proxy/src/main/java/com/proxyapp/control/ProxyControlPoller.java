package com.proxyapp.control;

import com.proxyapp.ingress.FtpIngressListener;
import com.proxyapp.ingress.TcpSocketServer;
import com.proxyapp.routing.RoutingState;
import com.proxyapp.session.DeviceSessionStatus;
import com.proxyapp.session.TcpSessionManager;
import io.temporal.client.WorkflowClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Queries the control workflow on a short interval, hands changed state to the Reconciler,
 * reports the applied state back (so the cloud can see desired vs applied), and acts on
 * lifecycle commands. This poll (plus worker polling) is the proxy's entire control
 * surface — everything rides the egress gRPC connection; no inbound port is ever opened.
 */
public class ProxyControlPoller implements SmartLifecycle {

    /**
     * Exit code asking the supervisor wrapper (see {@code just run-proxy-managed}, or
     * systemd in production) to relaunch the process. Plain shutdown exits 0 and stays down.
     */
    public static final int RESTART_EXIT_CODE = 10;

    private static final Logger log = LoggerFactory.getLogger(ProxyControlPoller.class);
    private static final long POLL_INTERVAL_MS = 2_000;
    /** Whether something will relaunch us after exit 10 (supervisor wrapper / systemd). */
    private static final boolean SUPERVISED =
            Boolean.parseBoolean(System.getenv().getOrDefault("PROXY_SUPERVISED", "false"));

    private final WorkflowClient workflowClient;
    private final ProxyControlStarter starter;
    private final Reconciler reconciler;
    private final RoutingState routingState;
    private final TcpSocketServer tcpSocketServer;
    private final FtpIngressListener ftpIngressListener;
    private final TcpSessionManager tcpSessionManager;
    private final ApplicationContext applicationContext;
    private final String startedAt = Instant.now().toString();
    private final AtomicBoolean exiting = new AtomicBoolean();
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "proxy-control-poller");
                t.setDaemon(true);
                return t;
            });
    private volatile boolean running;
    private volatile long lastReportedVersion = -1;
    private volatile Boolean lastReportedEnabled;
    private volatile String lastReportedSessionSig = "";

    public ProxyControlPoller(WorkflowClient workflowClient, ProxyControlStarter starter,
                              Reconciler reconciler, RoutingState routingState,
                              TcpSocketServer tcpSocketServer, FtpIngressListener ftpIngressListener,
                              TcpSessionManager tcpSessionManager,
                              ApplicationContext applicationContext) {
        this.workflowClient = workflowClient;
        this.starter = starter;
        this.reconciler = reconciler;
        this.routingState = routingState;
        this.tcpSocketServer = tcpSocketServer;
        this.ftpIngressListener = ftpIngressListener;
        this.tcpSessionManager = tcpSessionManager;
        this.applicationContext = applicationContext;
    }

    @Override
    public void start() {
        running = true;
        executor.scheduleWithFixedDelay(this::pollOnce, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void pollOnce() {
        if (exiting.get()) {
            return;
        }
        try {
            ProxyControlWorkflow stub = workflowClient.newWorkflowStub(
                    ProxyControlWorkflow.class, ProxyControlWorkflow.WORKFLOW_ID);
            ProxyControlState state = stub.getState();
            if (state.getVersion() != routingState.appliedVersion()
                    || state.isEnabled() != routingState.enabled()) {
                reconciler.apply(state);
            }
            maybeReportApplied(stub);
            handleLifecycle(stub, state);
        } catch (Exception e) {
            // Not started yet (fresh namespace) or transient connectivity — ensure and retry.
            log.debug("control poll failed ({}); ensuring control workflow exists", e.toString());
            try {
                starter.ensureStarted();
            } catch (Exception startFailure) {
                log.warn("cannot reach control workflow: {}", startFailure.getMessage());
            }
        }
    }

    /**
     * Signal the applied state back when it changes (and once at startup) — including per-device
     * link transitions (UP/DOWN/CONNECTING). Heartbeats themselves never trigger a report: the
     * signature is state-only, so a steady link is silent and only transitions reach Temporal.
     */
    private void maybeReportApplied(ProxyControlWorkflow stub) {
        long appliedVersion = routingState.appliedVersion();
        boolean enabled = routingState.enabled();
        List<DeviceSessionStatus> sessions = tcpSessionManager.statuses();
        String sessionSig = sessionSignature(sessions);
        if (appliedVersion == lastReportedVersion
                && lastReportedEnabled != null && lastReportedEnabled == enabled
                && sessionSig.equals(lastReportedSessionSig)) {
            return;
        }
        AppliedStatus status = new AppliedStatus(appliedVersion, enabled,
                routingState.table().inboundHttpPaths().stream().sorted().toList(),
                tcpSocketServer.activePorts().stream().sorted().toList(),
                ftpIngressListener.activeFolders().stream().sorted().toList(),
                startedAt, Instant.now().toString(), SUPERVISED, sessions);
        stub.reportApplied(status);
        lastReportedVersion = appliedVersion;
        lastReportedEnabled = enabled;
        lastReportedSessionSig = sessionSig;
        log.info("reported applied state v{} (enabled={}, sessions=[{}]) to control workflow",
                appliedVersion, enabled, sessionSig);
    }

    /** deviceId:state pairs only — flips on link transitions, not on every heartbeat. */
    private static String sessionSignature(List<DeviceSessionStatus> sessions) {
        return String.join(",", sessions.stream()
                .map(s -> s.deviceId() + ":" + s.state())
                .toList());
    }

    /** Act on a pending shutdown/restart command: ack it, then exit the JVM gracefully. */
    private void handleLifecycle(ProxyControlWorkflow stub, ProxyControlState state) {
        String command = state.getLifecycleCommand();
        String requestId = state.getLifecycleRequestId();
        if (command == null || requestId == null
                || ProxyControlState.LIFECYCLE_NONE.equals(command)) {
            return;
        }
        if (!exiting.compareAndSet(false, true)) {
            return;
        }
        // Ack first so the cleared command is durable before the process goes away —
        // otherwise the relaunched proxy would see the same command and exit again.
        stub.ackLifecycle(requestId);
        int exitCode = ProxyControlState.LIFECYCLE_RESTART.equals(command) ? RESTART_EXIT_CODE : 0;
        log.info("lifecycle command '{}' received from cloud — exiting with code {}", command, exitCode);
        if (exitCode == RESTART_EXIT_CODE && !SUPERVISED) {
            log.warn("no supervisor detected (PROXY_SUPERVISED unset) — nothing will relaunch "
                    + "this process; run via 'just run-proxy-managed' or a service unit");
        }
        Thread exitThread = new Thread(() -> {
            int code = SpringApplication.exit(applicationContext, () -> exitCode);
            System.exit(code);
        }, "proxy-lifecycle-exit");
        exitThread.setDaemon(false);
        exitThread.start();
    }

    @Override
    public void stop() {
        running = false;
        executor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // After the Temporal workers (which start on ApplicationReadyEvent-ish phases).
        return Integer.MAX_VALUE - 100;
    }
}
