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
 * Acts on a lifecycle command the control workflow pushed via {@code deliverLifecycle}. Exits on a
 * delayed thread, never the activity thread: an activity that kills its worker before its completion
 * is recorded gets retried on relaunch and exits again.
 *
 * <p>The proxy cannot restart itself, only exit. Run it under something that relaunches on
 * {@link #RESTART_EXIT_CODE} and set {@code PROXY_SUPERVISED=true} there; without one, restart
 * degrades to shutdown.
 */
public class LifecycleController {

    /** Exit code asking whatever supervises this process to relaunch it. Any other code stays down. */
    public static final int RESTART_EXIT_CODE = 10;

    private static final Logger log = LoggerFactory.getLogger(LifecycleController.class);
    /** Set by the deployment to declare that the process will be relaunched after a restart exit. */
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
        // Ack FIRST so the cleared command is durable before the process goes away, else the
        // relaunched proxy sees the same command and exits again. A throw here leaves us alive
        // with exiting=false, so a later attempt can retry.
        workflowClient.newWorkflowStub(ProxyControlWorkflow.class, ProxyControlWorkflow.WORKFLOW_ID)
                .ackLifecycle(requestId);
        if (!exiting.compareAndSet(false, true)) {
            return; // a delivery is already in flight in this process
        }
        int exitCode = ProxyControlState.LIFECYCLE_RESTART.equals(command) ? RESTART_EXIT_CODE : 0;
        log.info("lifecycle command '{}' received from cloud — exiting with code {}", command, exitCode);
        if (exitCode == RESTART_EXIT_CODE && !SUPERVISED) {
            log.warn("no supervisor detected (PROXY_SUPERVISED unset) — nothing will relaunch this "
                    + "process, so this restart will behave as a shutdown; run under a supervisor "
                    + "that restarts on exit code {} (e.g. a systemd unit with Restart=on-failure) "
                    + "and set PROXY_SUPERVISED=true", RESTART_EXIT_CODE);
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
