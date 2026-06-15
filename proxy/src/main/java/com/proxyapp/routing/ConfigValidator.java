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
            if (device.tcpSession() != null) {
                validateTcpSession(id + ": tcpSession", device, device.tcpSession(),
                        typeDirections, errors);
            }
            for (RouteBinding binding : device.bindings()) {
                validateBinding(typeDirections, pool, inboundChannelOwners, device, binding, errors);
            }
        }
        validateServerPorts(devices, errors);
        return errors;
    }

    /**
     * Persistent SERVER devices may share one listen port (port economy), but only if each carries a
     * distinct, non-blank handshakeId so the proxy can tell them apart on connect. Mirrored in
     * validate.ts.
     */
    private static void validateServerPorts(List<EdgeConfig> devices, List<String> errors) {
        Map<Integer, List<EdgeConfig>> byPort = new HashMap<>();
        for (EdgeConfig device : devices) {
            TcpSession s = device.tcpSession();
            if (s != null && s.isPersistent() && s.role() == TcpSession.Role.SERVER
                    && s.listenPort() != null) {
                byPort.computeIfAbsent(s.listenPort(), k -> new ArrayList<>()).add(device);
            }
        }
        for (Map.Entry<Integer, List<EdgeConfig>> entry : byPort.entrySet()) {
            List<EdgeConfig> group = entry.getValue();
            if (group.size() < 2) {
                continue; // a dedicated port needs no handshake
            }
            Set<String> seen = new HashSet<>();
            for (EdgeConfig device : group) {
                String handshakeId = device.tcpSession().handshakeId();
                if (handshakeId == null || handshakeId.isBlank()) {
                    errors.add(device.deviceId() + ": tcpSession: SERVER listen port " + entry.getKey()
                            + " is shared, so a handshakeId is required");
                } else if (!seen.add(handshakeId)) {
                    errors.add(device.deviceId() + ": tcpSession: duplicate handshakeId '" + handshakeId
                            + "' on shared SERVER listen port " + entry.getKey());
                }
            }
        }
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

    /**
     * Persistent-session rules, applied only in PERSISTENT mode. Mirrored verbatim in the
     * management UI's validate.ts — message text must stay identical. CLIENT needs host+port;
     * SERVER needs listenPort or a handshake id; a persistent session needs at least one liveness
     * mechanism; all heartbeat WireString fields must parse.
     */
    private static void validateTcpSession(String prefix, EdgeConfig device, TcpSession s,
                                           Map<String, String> typeDirections, List<String> errors) {
        if (s.mode() != TcpSession.Mode.PERSISTENT) {
            return; // PER_MESSAGE / unset = today's connect-per-message behavior, nothing to check
        }
        if (s.role() == null) {
            errors.add(prefix + ": PERSISTENT session requires a role (CLIENT or SERVER)");
        } else if (s.role() == TcpSession.Role.CLIENT) {
            if (device.host() == null || device.host().isBlank()) {
                errors.add(prefix + ": CLIENT role requires the device host");
            }
            if (s.port() == null) {
                errors.add(prefix + ": CLIENT role requires a port");
            } else if (s.port() < 1 || s.port() > 65535) {
                errors.add(prefix + ".port must be between 1 and 65535");
            }
        } else {
            // SERVER always needs a port to listen on; handshakeId only disambiguates a shared port
            // (checked across devices in validateServerPorts).
            if (s.listenPort() == null) {
                errors.add(prefix + ": SERVER role requires a listenPort");
            } else if (s.listenPort() < 1 || s.listenPort() > 65535) {
                errors.add(prefix + ".listenPort must be between 1 and 65535");
            }
        }

        TcpSession.Heartbeat hb = s.heartbeat();
        boolean hasPing = hb != null && hb.hasOutboundPing();
        boolean hasWatchdog = hb != null && hb.hasInboundWatchdog();
        if (!hasPing && !hasWatchdog) {
            errors.add(prefix + ": PERSISTENT session requires at least one liveness mechanism "
                    + "(heartbeat.sendIntervalSec or heartbeat.expectInboundSec)");
        }
        if (hb != null) {
            String hbPrefix = prefix + ".heartbeat";
            checkWireField(hbPrefix, "sendPayload", hb.sendPayload(), errors);
            checkWireField(hbPrefix, "expectReply", hb.expectReply(), errors);
            checkPositive(hbPrefix, "sendIntervalSec", hb.sendIntervalSec(), errors);
            checkPositive(hbPrefix, "replyTimeoutMs", hb.replyTimeoutMs(), errors);
            checkPositive(hbPrefix, "expectInboundSec", hb.expectInboundSec(), errors);
            checkPositive(hbPrefix, "missThreshold", hb.missThreshold(), errors);
            if (hasPing && hb.sendPayload() == null) {
                errors.add(hbPrefix + ": sendIntervalSec requires sendPayload");
            }
            if (hb.expectReply() != null && !hasPing) {
                errors.add(hbPrefix + ": expectReply requires sendIntervalSec");
            }
        }

        if (s.inboundType() != null) {
            String direction = typeDirections.get(s.inboundType());
            if (direction == null) {
                errors.add(prefix + ": unknown inboundType '" + s.inboundType() + "'");
            } else if (Direction.valueOf(direction) != Direction.EDGE_TO_CLOUD) {
                errors.add(prefix + ": inboundType '" + s.inboundType()
                        + "' must be an EDGE_TO_CLOUD type");
            }
        }
        if (s.inboundType() != null && s.resolver() != null) {
            errors.add(prefix + ": set either inboundType or resolver, not both");
        }
        if (s.resolver() != null) {
            if (s.resolver().kind() == null || s.resolver().kind().isBlank()) {
                errors.add(prefix + ": resolver kind must not be blank");
            }
            if (s.resolver().patterns() != null) {
                for (String target : s.resolver().patterns().values()) {
                    String direction = typeDirections.get(target);
                    if (direction == null) {
                        errors.add(prefix + ": resolver maps to unknown type '" + target + "'");
                    } else if (Direction.valueOf(direction) != Direction.EDGE_TO_CLOUD) {
                        errors.add(prefix + ": resolver type '" + target
                                + "' must be an EDGE_TO_CLOUD type");
                    }
                }
            }
        }
    }

    private static void checkPositive(String prefix, String field, Integer value,
                                      List<String> errors) {
        if (value != null && value <= 0) {
            errors.add(prefix + "." + field + " must be positive");
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
