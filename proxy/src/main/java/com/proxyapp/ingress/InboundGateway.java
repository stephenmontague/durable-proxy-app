package com.proxyapp.ingress;

import com.proxyapp.codec.CodecRegistry;
import com.proxyapp.config.ProxyProperties;
import com.proxyapp.model.CanonicalMessage;
import com.proxyapp.routing.CatalogEntry;
import com.proxyapp.routing.MessageCatalog;
import com.proxyapp.routing.MessageType;
import com.proxyapp.routing.MessageTypeResolver;
import com.proxyapp.routing.MessageTypeResolver.InboundContext;
import com.proxyapp.routing.ResolverConfig;
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
import java.util.UUID;
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
        MessageCatalog catalog = routingState.table().catalog();
        ResolverConfig resolverConfig = config.session().resolver();
        CatalogEntry entry;
        if (resolverConfig != null) {
            entry = resolveSessionType(config, resolverConfig, raw, catalog);
        } else {
            String inboundType = config.session().inboundType();
            if (inboundType == null) {
                log.debug("device {} sent an unsolicited frame but no inboundType/resolver is "
                        + "configured; dropping", config.deviceId());
                return;
            }
            entry = catalog.entry(MessageType.of(inboundType)).orElse(null);
            if (entry == null) {
                log.warn("device {} inboundType '{}' is not in the catalog; dropping frame",
                        config.deviceId(), inboundType);
            }
        }
        if (entry == null) {
            return; // could not type the frame (already logged); drop it, keep the link up
        }
        enqueue(entry, raw, "device '" + config.deviceId() + "' session");
    }

    /** Type a session frame via its resolver (content rule), mapping the result to a catalog entry. */
    private CatalogEntry resolveSessionType(DeviceSessionConfig config, ResolverConfig resolverConfig,
                                            byte[] raw, MessageCatalog catalog) {
        MessageTypeResolver resolver = resolvers.get(resolverConfig.kind());
        if (resolver == null) {
            log.warn("device {} session resolver kind '{}' is not registered; dropping frame",
                    config.deviceId(), resolverConfig.kind());
            return null;
        }
        CatalogEntry entry = resolver
                .resolve(resolverConfig, new InboundContext(Transport.TCP, config.deviceId(), null, raw))
                .flatMap(catalog::entry)
                .orElse(null);
        if (entry == null) {
            log.warn("device {} session resolver could not type a frame; dropping", config.deviceId());
        }
        return entry;
    }

    /** Decode + start the durable DeliverToCloud activity (ack-after-enqueue, dedup by activity id). */
    private EnqueueResult enqueue(CatalogEntry entry, byte[] raw, String source) {
        CanonicalMessage message = codecRegistry.require(entry.codec()).decode(entry, raw);
        String activityId = activityId(entry, message);
        StartActivityOptions options = StartActivityOptions.newBuilder()
                .setId(activityId)
                .setTaskQueue(properties.taskQueue())
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .setIdReusePolicy(ActivityIdReusePolicy.ACTIVITY_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .setIdConflictPolicy(ActivityIdConflictPolicy.ACTIVITY_ID_CONFLICT_POLICY_USE_EXISTING)
                .build();
        try {
            activityClient.start(DeliverToCloudActivity.class, DeliverToCloudActivity::deliver,
                    options, message);
            log.info("enqueued {} from {}", activityId, source);
            return new EnqueueResult(activityId, false);
        } catch (ActivityAlreadyStartedException e) {
            // Already delivered (or in flight) — still ack so the device stops retrying.
            log.info("duplicate push for {} ignored", activityId);
            return new EnqueueResult(activityId, true);
        }
    }

    /**
     * The Temporal activity id that drives dedup. Normally {@code {type}-{businessId}}, so identical
     * pushes collapse to a single delivery (REJECT_DUPLICATE reuse policy). When the type sets
     * {@code allowDuplicates}, a unique suffix is appended so every push gets its own activity id —
     * for event/telemetry streams where two byte-identical frames are two real observations, not a
     * retransmit. Trade-off: a transport-level retry of one push can then double-deliver, so it stays
     * at-least-once. Package-private + static so the dedup decision is unit-testable without Temporal.
     */
    static String activityId(CatalogEntry entry, CanonicalMessage message) {
        return entry.allowDuplicates()
                ? message.activityId() + "-" + UUID.randomUUID()
                : message.activityId();
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
