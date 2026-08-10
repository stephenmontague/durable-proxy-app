package com.proxyapp.temporal.workflow;

import com.proxyapp.model.CanonicalMessage;
import com.proxyapp.temporal.activity.DeliverToEdgeActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * The cloud-to-edge entry point: a cloud application starts this to hand the proxy one message for
 * a device. Routing, encoding and transport all happen in the activity. Delivery is retried until
 * it succeeds, so start returning does not mean delivered.
 */
@WorkflowImpl(taskQueues = "${proxy.task-queue}")
public class DeliverToEdgeWorkflowImpl implements DeliverToEdgeWorkflow {

    private final DeliverToEdgeActivity activity = Workflow.newActivityStub(
            DeliverToEdgeActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .build());

    @Override
    public void deliver(CanonicalMessage message) {
        activity.deliver(message);
    }
}
