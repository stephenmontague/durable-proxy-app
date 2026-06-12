package com.proxyapp.temporal.activity;

import com.proxyapp.codec.CodecRegistry;
import com.proxyapp.connector.ChannelTarget;
import com.proxyapp.connector.ConnectorFactory;
import com.proxyapp.model.CanonicalMessage;
import com.proxyapp.routing.MessageType;
import com.proxyapp.routing.RouteTable;
import com.proxyapp.routing.RoutingState;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@ActivityImpl(taskQueues = "${proxy.task-queue}")
public class DeliverToEdgeActivityImpl implements DeliverToEdgeActivity {

    private static final Logger log = LoggerFactory.getLogger(DeliverToEdgeActivityImpl.class);

    private final RoutingState routingState;
    private final CodecRegistry codecRegistry;
    private final ConnectorFactory connectorFactory;

    public DeliverToEdgeActivityImpl(RoutingState routingState, CodecRegistry codecRegistry,
                                     ConnectorFactory connectorFactory) {
        this.routingState = routingState;
        this.codecRegistry = codecRegistry;
        this.connectorFactory = connectorFactory;
    }

    @Override
    public void deliver(CanonicalMessage message) {
        RouteTable table = routingState.table();
        // Retryable on purpose: if the route isn't configured yet, the activity keeps
        // retrying and delivers as soon as ops binds the type to a channel.
        RouteTable.OutboundRoute route = table
                .resolveOutbound(MessageType.of(message.messageType()))
                .orElseThrow(() -> ApplicationFailure.newFailure(
                        "no outbound route configured for type " + message.messageType(),
                        "RouteNotConfigured"));

        byte[] payload = codecRegistry.require(route.entry().codec()).encode(message);
        ChannelTarget target = toTarget(route, message);
        connectorFactory.require(route.binding().transport()).send(target, payload);
        log.info("delivered {} to edge device '{}' over {} {}", message.activityId(),
                route.device().deviceId(), route.binding().transport(), route.binding().channel());
    }

    private static ChannelTarget toTarget(RouteTable.OutboundRoute route, CanonicalMessage message) {
        var device = route.device();
        var channel = route.binding().channel();
        return switch (route.binding().transport()) {
            case HTTP -> new ChannelTarget.HttpTarget(device.baseUrl() + channel.value());
            case TCP -> new ChannelTarget.TcpTarget(device.host(), channel.portValue(),
                    route.effectiveTcpProtocol());
            case FTP -> new ChannelTarget.FtpTarget(device.host(), device.ftpPort(),
                    device.ftpUser(), device.ftpPassword(), channel.value(),
                    message.activityId() + ".json");
        };
    }
}
