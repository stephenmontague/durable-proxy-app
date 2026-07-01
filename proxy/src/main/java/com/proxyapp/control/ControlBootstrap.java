package com.proxyapp.control;
import com.proxyapp.temporal.workflow.ProxyControlWorkflow;

import io.temporal.client.WorkflowClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Replaces the old 2s control poll. On start it ensures the control workflow exists and sends one
 * {@code requestReconcile} so the proxy applies current desired state immediately — covering both a
 * fresh install and a proxy restart against an already-running workflow (which won't re-invoke
 * {@code run()}). Retries until the first success (Temporal may be briefly unreachable at boot),
 * then stops. After that every reconcile is pushed by the workflow; there is no recurring poll.
 */
public class ControlBootstrap implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ControlBootstrap.class);
    private static final long RETRY_INTERVAL_MS = 5_000;

    private final WorkflowClient workflowClient;
    private final ProxyControlStarter starter;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "control-bootstrap");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean running = new AtomicBoolean();

    public ControlBootstrap(WorkflowClient workflowClient, ProxyControlStarter starter) {
        this.workflowClient = workflowClient;
        this.starter = starter;
    }

    @Override
    public void start() {
        running.set(true);
        executor.execute(this::attempt);
    }

    private void attempt() {
        if (!running.get()) {
            return;
        }
        try {
            starter.ensureStarted();
            workflowClient.newWorkflowStub(ProxyControlWorkflow.class, ProxyControlWorkflow.WORKFLOW_ID)
                    .requestReconcile();
            log.info("control workflow ensured and initial reconcile requested");
        } catch (Exception e) {
            log.warn("control bootstrap failed ({}); retrying in {}ms", e.getMessage(), RETRY_INTERVAL_MS);
            executor.schedule(this::attempt, RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void stop() {
        running.set(false);
        executor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        // After the Temporal workers come up (the late phase the old poller used).
        return Integer.MAX_VALUE - 100;
    }
}
