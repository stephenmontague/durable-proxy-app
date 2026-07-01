package com.proxyapp.control;
import com.proxyapp.control.model.ProxyControlState;
import com.proxyapp.temporal.workflow.ProxyControlWorkflow;

import io.temporal.client.WorkflowClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Acts on a lifecycle command (restart/shutdown) the control workflow pushed via the
 * {@code deliverLifecycle} activity. Acks first so the cleared command is durable before the JVM
 * goes away, then exits on a separate, slightly-delayed thread — never on the activity thread,
 * because an activity that kills its own worker before its completion is recorded would be retried
 * on relaunch and exit again. Exit code 10 asks the supervisor to relaunch; 0 stays down.
 */
public class LifecycleController {

    /** Exit code asking the supervisor wrapper (see {@code just run-proxy-managed} / systemd) to relaunch. */
    public static final int RESTART_EXIT_CODE = 10;

    private static final Logger log = LoggerFactory.getLogger(LifecycleController.class);
    /** Whether something will relaunch us after exit 10 (supervisor wrapper / systemd). */
    private static final boolean SUPERVISED =
            Boolean.parseBoolean(System.getenv().getOrDefault("PROXY_SUPERVISED", "false"));
    /** Let the activity completion record before the JVM exits (attempt-once delivery). */
    private static final long EXIT_DELAY_MS = 1_500;

    private final WorkflowClient workflowClient;
    private final ApplicationContext applicationContext;
    private final AtomicBoolean exiting = new AtomicBoolean();

    public LifecycleController(WorkflowClient workflowClient, ApplicationContext applicationContext) {
        this.workflowClient = workflowClient;
        this.applicationContext = applicationContext;
    }

    public void deliver(String command, String requestId) {
        if (command == null || requestId == null
                || ProxyControlState.LIFECYCLE_NONE.equals(command)) {
            return;
        }
        // Ack FIRST so the cleared command is durable before the process goes away — otherwise the
        // relaunched proxy would see the same command and exit again. A failure here throws (the
        // activity fails) and leaves us alive with exiting=false, so a later attempt can retry.
        workflowClient.newWorkflowStub(ProxyControlWorkflow.class, ProxyControlWorkflow.WORKFLOW_ID)
                .ackLifecycle(requestId);
        if (!exiting.compareAndSet(false, true)) {
            return; // a delivery is already in flight in this process
        }
        int exitCode = ProxyControlState.LIFECYCLE_RESTART.equals(command) ? RESTART_EXIT_CODE : 0;
        log.info("lifecycle command '{}' received from cloud — exiting with code {}", command, exitCode);
        if (exitCode == RESTART_EXIT_CODE && !SUPERVISED) {
            log.warn("no supervisor detected (PROXY_SUPERVISED unset) — nothing will relaunch "
                    + "this process; run via 'just run-proxy-managed' or a service unit");
        }
        Thread exitThread = new Thread(() -> {
            sleepQuietly();
            int code = SpringApplication.exit(applicationContext, () -> exitCode);
            System.exit(code);
        }, "proxy-lifecycle-exit");
        exitThread.setDaemon(false);
        exitThread.start();
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(EXIT_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
