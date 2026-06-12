package com.proxyapp.routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a proposed routing config before it goes live. Pure and deterministic so the
 * same checks run inside the control workflow's signal handlers (against the slim
 * {@code typeDirections} view) and on the proxy before applying.
 */
public final class ConfigValidator {

    private ConfigValidator() {
    }

    /** Convenience overload for proxy-side validation against the full catalog. */
    public static List<String> validate(MessageCatalog catalog, List<Integer> tcpPortPool,
                                        List<EdgeConfig> devices) {
        return validate(catalog.typeDirections(), tcpPortPool, devices);
    }

    /**
     * @param typeDirections type name -> Direction name, from the catalog
     * @param tcpPortPool    inbound TCP ports IT made available at install time
     * @param devices        the proposed config
     * @return human-readable errors; empty means the config is valid
     */
    public static List<String> validate(Map<String, String> typeDirections,
                                        List<Integer> tcpPortPool, List<EdgeConfig> devices) {
        List<String> errors = new ArrayList<>();
        if (devices == null) {
            errors.add("devices must not be null");
            return errors;
        }
        Set<String> deviceIds = new HashSet<>();
        Map<String, String> inboundChannelOwners = new HashMap<>();
        Set<Integer> pool = tcpPortPool == null ? Set.of() : new HashSet<>(tcpPortPool);

        for (EdgeConfig device : devices) {
            if (device.deviceId() == null || device.deviceId().isBlank()) {
                errors.add("device with missing deviceId");
                continue;
            }
            String id = device.deviceId();
            if (!deviceIds.add(id)) {
                errors.add("duplicate deviceId: " + id);
            }
            if (device.tcpProtocol() != null) {
                validateTcpProtocol(id + ": device tcpProtocol", device.tcpProtocol(), errors);
            }
            for (RouteBinding binding : device.bindings()) {
                validateBinding(typeDirections, pool, inboundChannelOwners, device, binding, errors);
            }
        }
        return errors;
    }

    private static void validateBinding(Map<String, String> typeDirections, Set<Integer> pool,
                                        Map<String, String> inboundChannelOwners,
                                        EdgeConfig device, RouteBinding binding,
                                        List<String> errors) {
        String id = device.deviceId();
        if (binding.transport() == null || binding.channel() == null) {
            errors.add(id + ": binding missing transport or channel");
            return;
        }
        ChannelKind expectedKind = switch (binding.transport()) {
            case HTTP -> ChannelKind.PATH;
            case TCP -> ChannelKind.PORT;
            case FTP -> ChannelKind.FOLDER;
        };
        if (binding.channel().kind() != expectedKind) {
            errors.add(id + ": " + binding.transport() + " binding requires a "
                    + expectedKind + " channel, got " + binding.channel());
            return;
        }

        if (binding.tcpProtocol() != null) {
            if (binding.transport() != Transport.TCP) {
                errors.add(id + ": tcpProtocol override requires TCP transport, got "
                        + binding.transport());
            } else {
                String label = binding.messageType() != null
                        ? binding.messageType().value() : binding.channel().value();
                validateTcpProtocol(id + ": " + label + " tcpProtocol",
                        binding.tcpProtocol(), errors);
            }
        }

        if (binding.isMultiType()) {
            if (binding.transport() != Transport.FTP) {
                errors.add(id + ": multi-type resolver bindings are only supported on FTP folders");
            }
            claimInbound(inboundChannelOwners, device, binding, "multi-type:" + binding.channel(), errors);
            return;
        }

        if (binding.messageType() == null) {
            errors.add(id + ": binding missing messageType");
            return;
        }
        String typeName = binding.messageType().value();
        String directionName = typeDirections.get(typeName);
        if (directionName == null) {
            errors.add(id + ": unknown message type " + typeName);
            return;
        }

        if (Direction.valueOf(directionName) == Direction.EDGE_TO_CLOUD) {
            if (binding.transport() == Transport.TCP) {
                int port = binding.channel().portValue();
                if (!pool.contains(port)) {
                    errors.add(id + ": inbound TCP port " + port + " for " + typeName
                            + " is outside the available port pool " + sorted(pool));
                }
            }
            claimInbound(inboundChannelOwners, device, binding, typeName, errors);
        } else {
            switch (binding.transport()) {
                case HTTP -> {
                    if (device.baseUrl() == null || device.baseUrl().isBlank()) {
                        errors.add(id + ": outbound HTTP binding for " + typeName
                                + " requires the device baseUrl");
                    }
                }
                case TCP -> {
                    if (device.host() == null || device.host().isBlank()) {
                        errors.add(id + ": outbound TCP binding for " + typeName
                                + " requires the device host");
                    }
                }
                case FTP -> {
                    if (device.host() == null || device.host().isBlank() || device.ftpPort() == null) {
                        errors.add(id + ": outbound FTP binding for " + typeName
                                + " requires the device host and ftpPort");
                    }
                }
            }
        }
    }

    /**
     * Wire-protocol rules. Mirrored verbatim in the management UI's validate.ts —
     * message text must stay identical (operators compare UI errors with lastError).
     */
    private static void validateTcpProtocol(String prefix, TcpProtocol p, List<String> errors) {
        checkWireField(prefix, "startDelimiter", p.startDelimiter(), errors);
        checkWireField(prefix, "endDelimiter", p.endDelimiter(), errors);
        checkWireField(prefix, "ackReply", p.ackReply(), errors);
        checkWireField(prefix, "nakReply", p.nakReply(), errors);
        checkWireField(prefix, "expectedAck", p.expectedAck(), errors);
        if (p.startDelimiter() != null && p.endDelimiter() == null) {
            errors.add(prefix + ": startDelimiter requires endDelimiter");
        }
        if (Boolean.FALSE.equals(p.awaitReply()) && p.expectedAck() != null) {
            errors.add(prefix + ": expectedAck is meaningless when awaitReply is false");
        }
    }

    private static void checkWireField(String prefix, String field, String value,
                                       List<String> errors) {
        if (value == null) {
            return;
        }
        if (value.isEmpty()) {
            errors.add(prefix + "." + field + " must not be empty");
            return;
        }
        String error = WireString.validate(value);
        if (error != null) {
            errors.add(prefix + "." + field + ": " + error);
        }
    }

    private static void claimInbound(Map<String, String> owners, EdgeConfig device,
                                     RouteBinding binding, String claimant, List<String> errors) {
        // Inbound channels are proxy-wide resources: one channel carries exactly one type.
        String key = binding.transport() + "|" + binding.channel().value();
        String previous = owners.putIfAbsent(key, device.deviceId() + "/" + claimant);
        if (previous != null) {
            errors.add("inbound channel collision on " + binding.transport() + " "
                    + binding.channel() + ": already used by " + previous);
        }
    }

    private static List<Integer> sorted(Set<Integer> pool) {
        return pool.stream().sorted().toList();
    }
}
