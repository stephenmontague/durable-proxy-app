package com.proxyapp.ingress;

import com.proxyapp.codec.CodecRegistry;
import com.proxyapp.config.ProxyProperties;
import com.proxyapp.model.CanonicalMessage;
import com.proxyapp.routing.CatalogEntry;
import com.proxyapp.routing.MessageType;
import com.proxyapp.routing.MessageTypeResolver;
import com.proxyapp.routing.MessageTypeResolver.InboundContext;
import com.proxyapp.routing.RouteTable;
import com.proxyapp.routing.RoutingState;
import com.proxyapp.routing.Transport;
import com.proxyapp.session.DeviceSessionConfig;
import com.proxyapp.temporal.activity.DeliverToCloudActivity;
import io.temporal.api.enums.v1.ActivityIdConflictPolicy;
import io.temporal.api.enums.v1.ActivityIdReusePolicy;
import io.temporal.client.ActivityAlreadyStartedException;
import io.temporal.client.ActivityClient;
import io.temporal.client.StartActivityOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The single funnel for all inbound (edge -> cloud) traffic: channel -> type -> decode ->
 * start a durable {@code DeliverToCloud} standalone activity -> ack.
 *
 * <p>Ack-after-enqueue: the transport listener only acks the edge target after Temporal has
 * accepted the activity start, so a retrying device gets correct semantics. A duplicate
 * push (same activity id) is acked as already-enqueued, not re-executed.
 */
public class InboundGateway {

    public record EnqueueResult(String activityId, boolean duplicate) {
    }

    private static final Logger log = LoggerFactory.getLogger(InboundGateway.class);

    private final RoutingState routingState;
    private final CodecRegistry codecRegistry;
    private final ActivityClient activityClient;
    private final ProxyProperties properties;
    private final Map<String, MessageTypeResolver> resolvers;

    public InboundGateway(RoutingState routingState, CodecRegistry codecRegistry,
                          ActivityClient activityClient, ProxyProperties properties,
                          List<MessageTypeResolver> resolvers) {
        this.routingState = routingState;
        this.codecRegistry = codecRegistry;
        this.activityClient = activityClient;
        this.properties = properties;
        this.resolvers = resolvers.stream()
                .collect(Collectors.toMap(MessageTypeResolver::kind, Function.identity()));
    }

    public EnqueueResult handle(Transport transport, String channelValue, String filename,
                                byte[] raw) {
        if (!routingState.enabled()) {
            throw new IngressException(IngressException.Reason.DISABLED,
                    "proxy install is disabled");
        }
        RouteTable table = routingState.table();
        RouteTable.InboundRoute route = table.resolveInbound(transport, channelValue)
                .orElseThrow(() -> new IngressException(IngressException.Reason.UNKNOWN_CHANNEL,
                        "no inbound binding for " + transport + " channel '" + channelValue + "'"));

        CatalogEntry entry = route.isMultiType()
                ? resolveMultiType(table, route, transport, channelValue, filename, raw)
                : route.entry();

        return enqueue(entry, raw, transport + " channel '" + channelValue + "'");
    }

    /**
     * Type and enqueue an unsolicited frame from a device's persistent TCP session. The session has
     * no inbound channel binding, so the type comes from {@code tcpSession.inboundType} (an
     * EDGE_TO_CLOUD type). Frames with no inbound type configured — or while the install is
     * disabled — are dropped; the device link stays up regardless. (Multi-type sockets would
     * instead resolve each frame via a {@link MessageTypeResolver}, the documented extension.)
     */
    public void enqueueSessionFrame(DeviceSessionConfig config, byte[] raw) {
        if (!routingState.enabled()) {
            return;
        }
        String inboundType = config.session().inboundType();
        if (inboundType == null) {
            log.debug("device {} sent an unsolicited frame but no inboundType is configured; dropping",
                    config.deviceId());
            return;
        }
        CatalogEntry entry = routingState.table().catalog().entry(MessageType.of(inboundType)).orElse(null);
        if (entry == null) {
            log.warn("device {} inboundType '{}' is not in the catalog; dropping frame",
                    config.deviceId(), inboundType);
            return;
        }
        enqueue(entry, raw, "device '" + config.deviceId() + "' session");
    }

    /** Decode + start the durable DeliverToCloud activity (ack-after-enqueue, dedup by activity id). */
    private EnqueueResult enqueue(CatalogEntry entry, byte[] raw, String source) {
        CanonicalMessage message = codecRegistry.require(entry.codec()).decode(entry, raw);
        StartActivityOptions options = StartActivityOptions.newBuilder()
                .setId(message.activityId())
                .setTaskQueue(properties.taskQueue())
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .setIdReusePolicy(ActivityIdReusePolicy.ACTIVITY_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .setIdConflictPolicy(ActivityIdConflictPolicy.ACTIVITY_ID_CONFLICT_POLICY_USE_EXISTING)
                .build();
        try {
            activityClient.start(DeliverToCloudActivity.class, DeliverToCloudActivity::deliver,
                    options, message);
            log.info("enqueued {} from {}", message.activityId(), source);
            return new EnqueueResult(message.activityId(), false);
        } catch (ActivityAlreadyStartedException e) {
            // Already delivered (or in flight) — still ack so the device stops retrying.
            log.info("duplicate push for {} ignored", message.activityId());
            return new EnqueueResult(message.activityId(), true);
        }
    }

    private CatalogEntry resolveMultiType(RouteTable table, RouteTable.InboundRoute route,
                                          Transport transport, String channelValue,
                                          String filename, byte[] raw) {
        var resolverConfig = route.binding().resolver();
        MessageTypeResolver resolver = resolvers.get(resolverConfig.kind());
        if (resolver == null) {
            throw new IngressException(IngressException.Reason.UNRESOLVED_TYPE,
                    "no resolver of kind '" + resolverConfig.kind() + "'");
        }
        MessageType type = resolver
                .resolve(resolverConfig, new InboundContext(transport, channelValue, filename, raw))
                .orElseThrow(() -> new IngressException(IngressException.Reason.UNRESOLVED_TYPE,
                        "resolver '" + resolverConfig.kind() + "' could not type '" + filename + "'"));
        return table.catalog().require(type);
    }
}
