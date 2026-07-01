package com.proxyapp.temporal.activity;
import com.proxyapp.control.AppliedStatusReporter;
import com.proxyapp.control.LifecycleController;
import com.proxyapp.control.Reconciler;
import com.proxyapp.control.model.AppliedStatus;
import com.proxyapp.control.model.ProxyControlState;

import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

/**
 * Runs the control activities in the proxy process. Registered on the control task queue alongside
 * the {@link com.proxyapp.temporal.workflow.ProxyControlWorkflow}, so the workflow's pushes land on
 * this same worker.
 */
@Component
@ActivityImpl(taskQueues = "${proxy.control-task-queue}")
public class ControlActivitiesImpl implements ControlActivities {

    private final Reconciler reconciler;
    private final AppliedStatusReporter reporter;
    private final LifecycleController lifecycleController;

    public ControlActivitiesImpl(Reconciler reconciler, AppliedStatusReporter reporter,
                                 LifecycleController lifecycleController) {
        this.reconciler = reconciler;
        this.reporter = reporter;
        this.lifecycleController = lifecycleController;
    }

    @Override
    public AppliedStatus reconcile(ProxyControlState desired) {
        reconciler.apply(desired);
        AppliedStatus applied = reporter.snapshot();
        // The workflow records this return value, so adopt it as the reporter's baseline to keep the
        // link-health timer from re-reporting the same state as a redundant Action.
        reporter.syncBaseline(applied);
        return applied;
    }

    @Override
    public void deliverLifecycle(String command, String requestId) {
        lifecycleController.deliver(command, requestId);
    }
}
