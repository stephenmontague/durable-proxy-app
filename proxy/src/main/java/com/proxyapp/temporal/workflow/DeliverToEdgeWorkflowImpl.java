package com.proxyapp.temporal.workflow;

import com.proxyapp.model.CanonicalMessage;
import com.proxyapp.temporal.activity.DeliverToEdgeActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * The cloud-to-edge entry point: a cloud application starts this workflow to hand the proxy one
 * message for a device. It is a thin durable wrapper — routing, encoding and transport all happen
 * inside the activity, on the proxy worker that owns the device connection.
 *
 * <p>Delivery is retried by the activity's retry policy until it succeeds, so the caller's
 * guarantee is "this will reach the device or the workflow will still be trying", not "delivered
 * by the time start returns".
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
