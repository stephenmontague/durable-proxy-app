package com.proxyapp.ingress;

import com.proxyapp.codec.CodecRegistry;
import com.proxyapp.config.ProxyProperties;
import com.proxyapp.model.CanonicalMessage;
import com.proxyapp.routing.MessageCatalog;
import com.proxyapp.routing.MessageTypeResolver;
import com.proxyapp.routing.MessageTypeResolver.InboundContext;
import com.proxyapp.routing.RouteTable;
import com.proxyapp.routing.RoutingState;
import com.proxyapp.routing.model.CatalogEntry;
import com.proxyapp.routing.model.MessageType;
import com.proxyapp.routing.model.ResolverConfig;
import com.proxyapp.routing.model.Transport;
import com.proxyapp.session.model.DeviceSessionConfig;
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
 * The single funnel for all inbound (edge -> cloud) traffic: channel -> type -> decode -> start a
 * durable {@code DeliverToCloud} activity -> ack. The listener acks only after Temporal accepted the
 * start, so a retrying device gets correct semantics; a duplicate push is acked as already-enqueued
 * rather than re-executed.
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
     * Type and enqueue an unsolicited frame from a device's persistent TCP session. The session has no
     * inbound channel binding, so the type comes from {@code tcpSession.inboundType}, or from a
     * {@link MessageTypeResolver} on multi-type sockets. Frames are dropped when no inbound type is
     * configured or the install is disabled; the device link stays up regardless.
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
        try {
            enqueue(entry, raw, "device '" + config.deviceId() + "' session");
        } catch (IngressException e) {
            // Drop the frame and keep the link up if the enqueue fails — tearing the socket down
            // would help nothing, and the device pushes fresh readings on its own cadence.
            log.warn("dropping session frame from device {} ({}): {}",
                    config.deviceId(), e.reason(), e.getMessage());
        }
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
        } catch (Exception e) {
            // Decoding already succeeded, so a failure here is the enqueue call itself. Surface it as
            // retryable so the transport does NOT ack (HTTP 503, TCP nak, FTP keeps the file) and the
            // device retries instead of the message being silently dropped.
            log.warn("could not enqueue {} from {}: {}", activityId, source, e.toString());
            throw new IngressException(IngressException.Reason.UPSTREAM_UNAVAILABLE,
                    "could not enqueue to Temporal: " + e.getMessage());
        }
    }

    /**
     * The activity id that drives dedup: {@code {type}-{businessId}}, so identical pushes collapse to
     * one delivery under REJECT_DUPLICATE. {@code allowDuplicates} appends a unique suffix instead,
     * giving every push its own id; the trade-off is that a transport retry can then double-deliver,
     * leaving those types at-least-once.
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
