import type {
  CatalogEntryDto,
  Direction,
  EdgeConfig,
  RouteBinding,
  TcpProtocol,
  TcpSession,
} from "@/lib/types";
import { validateWireString } from "@/lib/wire-string";

// Mirrors com.proxyapp.routing.ConfigValidator so the wizard can reject a bad config
// before signaling. The control workflow re-validates authoritatively on receipt.
// Error message text must match the Java side character-for-character.

const EXPECTED_KIND: Record<string, string> = {
  HTTP: "PATH",
  TCP: "PORT",
  FTP: "FOLDER",
};

export function validateConfig(
  typeDirections: Record<string, Direction>,
  tcpPortPool: number[],
  devices: EdgeConfig[],
): string[] {
  const errors: string[] = [];
  const deviceIds = new Set<string>();
  const inboundChannelOwners = new Map<string, string>();
  const pool = new Set(tcpPortPool);

  for (const device of devices) {
    if (!device.deviceId || device.deviceId.trim() === "") {
      errors.push("device with missing deviceId");
      continue;
    }
    if (deviceIds.has(device.deviceId)) {
      errors.push(`duplicate deviceId: ${device.deviceId}`);
    }
    deviceIds.add(device.deviceId);
    if (device.tcpProtocol != null) {
      validateTcpProtocol(`${device.deviceId}: device tcpProtocol`, device.tcpProtocol, errors);
    }
    if (device.tcpSession != null) {
      validateTcpSession(
        `${device.deviceId}: tcpSession`,
        device,
        device.tcpSession,
        typeDirections,
        errors,
      );
    }
    for (const binding of device.bindings) {
      validateBinding(typeDirections, pool, inboundChannelOwners, device, binding, errors);
    }
  }
  validateServerPorts(devices, errors);
  return errors;
}

// Mirrors ConfigValidator.validateServerPorts: persistent SERVER devices may share a listen port,
// but only with a distinct, non-blank handshakeId each so the proxy can tell them apart on connect.
function validateServerPorts(devices: EdgeConfig[], errors: string[]): void {
  const byPort = new Map<number, EdgeConfig[]>();
  for (const device of devices) {
    const s = device.tcpSession;
    if (s != null && s.mode === "PERSISTENT" && s.role === "SERVER" && s.listenPort != null) {
      const group = byPort.get(s.listenPort) ?? [];
      group.push(device);
      byPort.set(s.listenPort, group);
    }
  }
  for (const [port, group] of byPort) {
    if (group.length < 2) continue; // a dedicated port needs no handshake
    const seen = new Set<string>();
    for (const device of group) {
      const handshakeId = device.tcpSession?.handshakeId;
      if (handshakeId == null || handshakeId.trim() === "") {
        errors.push(
          `${device.deviceId}: tcpSession: SERVER listen port ${port} is shared, so a handshakeId is required`,
        );
      } else if (seen.has(handshakeId)) {
        errors.push(
          `${device.deviceId}: tcpSession: duplicate handshakeId '${handshakeId}' on shared SERVER listen port ${port}`,
        );
      } else {
        seen.add(handshakeId);
      }
    }
  }
}

function validateBinding(
  typeDirections: Record<string, Direction>,
  pool: Set<number>,
  inboundChannelOwners: Map<string, string>,
  device: EdgeConfig,
  binding: RouteBinding,
  errors: string[],
): void {
  const id = device.deviceId;
  if (!binding.transport || !binding.channel) {
    errors.push(`${id}: binding missing transport or channel`);
    return;
  }
  const expectedKind = EXPECTED_KIND[binding.transport];
  if (binding.channel.kind !== expectedKind) {
    errors.push(
      `${id}: ${binding.transport} binding requires a ${expectedKind} channel, got ${binding.channel.kind}`,
    );
    return;
  }

  if (binding.tcpProtocol != null) {
    if (binding.transport !== "TCP") {
      errors.push(`${id}: tcpProtocol override requires TCP transport, got ${binding.transport}`);
    } else {
      const label = binding.messageType != null ? binding.messageType : binding.channel.value;
      validateTcpProtocol(`${id}: ${label} tcpProtocol`, binding.tcpProtocol, errors);
    }
  }

  const isMultiType = binding.messageType == null && binding.resolver != null;
  if (isMultiType) {
    if (binding.transport !== "FTP") {
      errors.push(`${id}: multi-type resolver bindings are only supported on FTP folders`);
    }
    claimInbound(inboundChannelOwners, device, binding, `multi-type:${binding.channel.value}`, errors);
    return;
  }

  if (binding.messageType == null) {
    errors.push(`${id}: binding missing messageType`);
    return;
  }
  const direction = typeDirections[binding.messageType];
  if (!direction) {
    errors.push(`${id}: unknown message type ${binding.messageType}`);
    return;
  }

  if (direction === "EDGE_TO_CLOUD") {
    if (binding.transport === "TCP") {
      const port = Number(binding.channel.value);
      if (!pool.has(port)) {
        const sorted = [...pool].sort((a, b) => a - b);
        errors.push(
          `${id}: inbound TCP port ${port} for ${binding.messageType} is outside the available port pool [${sorted.join(", ")}]`,
        );
      }
    }
    claimInbound(inboundChannelOwners, device, binding, binding.messageType, errors);
  } else {
    switch (binding.transport) {
      case "HTTP":
        if (!device.baseUrl || device.baseUrl.trim() === "") {
          errors.push(
            `${id}: outbound HTTP binding for ${binding.messageType} requires the device baseUrl`,
          );
        }
        break;
      case "TCP":
        if (!device.host || device.host.trim() === "") {
          errors.push(
            `${id}: outbound TCP binding for ${binding.messageType} requires the device host`,
          );
        }
        break;
      case "FTP":
        if (!device.host || device.host.trim() === "" || device.ftpPort == null) {
          errors.push(
            `${id}: outbound FTP binding for ${binding.messageType} requires the device host and ftpPort`,
          );
        }
        break;
    }
  }
}

// Wire-protocol rules — mirrors ConfigValidator.validateTcpProtocol verbatim.
function validateTcpProtocol(prefix: string, p: TcpProtocol, errors: string[]): void {
  checkWireField(prefix, "startDelimiter", p.startDelimiter, errors);
  checkWireField(prefix, "endDelimiter", p.endDelimiter, errors);
  checkWireField(prefix, "ackReply", p.ackReply, errors);
  checkWireField(prefix, "nakReply", p.nakReply, errors);
  checkWireField(prefix, "expectedAck", p.expectedAck, errors);
  if (p.startDelimiter != null && p.endDelimiter == null) {
    errors.push(`${prefix}: startDelimiter requires endDelimiter`);
  }
  if (p.awaitReply === false && p.expectedAck != null) {
    errors.push(`${prefix}: expectedAck is meaningless when awaitReply is false`);
  }
}

function checkWireField(
  prefix: string,
  field: string,
  value: string | null | undefined,
  errors: string[],
): void {
  if (value == null) return;
  if (value === "") {
    errors.push(`${prefix}.${field} must not be empty`);
    return;
  }
  const error = validateWireString(value);
  if (error != null) {
    errors.push(`${prefix}.${field}: ${error}`);
  }
}

// Persistent-session rules — mirrors ConfigValidator.validateTcpSession verbatim. Applied only
// in PERSISTENT mode; error text must match the Java side character-for-character.
function validateTcpSession(
  prefix: string,
  device: EdgeConfig,
  s: TcpSession,
  typeDirections: Record<string, Direction>,
  errors: string[],
): void {
  if (s.mode !== "PERSISTENT") return;
  if (s.role == null) {
    errors.push(`${prefix}: PERSISTENT session requires a role (CLIENT or SERVER)`);
  } else if (s.role === "CLIENT") {
    if (!device.host || device.host.trim() === "") {
      errors.push(`${prefix}: CLIENT role requires the device host`);
    }
    if (s.port == null) {
      errors.push(`${prefix}: CLIENT role requires a port`);
    } else if (s.port < 1 || s.port > 65535) {
      errors.push(`${prefix}.port must be between 1 and 65535`);
    }
  } else {
    // SERVER always needs a port to listen on; handshakeId only disambiguates a shared port
    // (checked across devices in validateServerPorts).
    if (s.listenPort == null) {
      errors.push(`${prefix}: SERVER role requires a listenPort`);
    } else if (s.listenPort < 1 || s.listenPort > 65535) {
      errors.push(`${prefix}.listenPort must be between 1 and 65535`);
    }
  }

  const hb = s.heartbeat;
  const hasPing = hb != null && hb.sendIntervalSec != null;
  const hasWatchdog = hb != null && hb.expectInboundSec != null;
  if (!hasPing && !hasWatchdog) {
    errors.push(
      `${prefix}: PERSISTENT session requires at least one liveness mechanism (heartbeat.sendIntervalSec or heartbeat.expectInboundSec)`,
    );
  }
  if (hb != null) {
    const hbPrefix = `${prefix}.heartbeat`;
    checkWireField(hbPrefix, "sendPayload", hb.sendPayload, errors);
    checkWireField(hbPrefix, "expectReply", hb.expectReply, errors);
    checkPositive(hbPrefix, "sendIntervalSec", hb.sendIntervalSec, errors);
    checkPositive(hbPrefix, "replyTimeoutMs", hb.replyTimeoutMs, errors);
    checkPositive(hbPrefix, "expectInboundSec", hb.expectInboundSec, errors);
    checkPositive(hbPrefix, "missThreshold", hb.missThreshold, errors);
    if (hasPing && hb.sendPayload == null) {
      errors.push(`${hbPrefix}: sendIntervalSec requires sendPayload`);
    }
    if (hb.expectReply != null && !hasPing) {
      errors.push(`${hbPrefix}: expectReply requires sendIntervalSec`);
    }
  }

  if (s.inboundType != null) {
    const direction = typeDirections[s.inboundType];
    if (!direction) {
      errors.push(`${prefix}: unknown inboundType '${s.inboundType}'`);
    } else if (direction !== "EDGE_TO_CLOUD") {
      errors.push(`${prefix}: inboundType '${s.inboundType}' must be an EDGE_TO_CLOUD type`);
    }
  }
  if (s.inboundType != null && s.resolver != null) {
    errors.push(`${prefix}: set either inboundType or resolver, not both`);
  }
  if (s.resolver != null) {
    if (s.resolver.kind == null || s.resolver.kind.trim() === "") {
      errors.push(`${prefix}: resolver kind must not be blank`);
    }
    if (s.resolver.patterns != null) {
      for (const target of Object.values(s.resolver.patterns)) {
        const direction = typeDirections[target];
        if (!direction) {
          errors.push(`${prefix}: resolver maps to unknown type '${target}'`);
        } else if (direction !== "EDGE_TO_CLOUD") {
          errors.push(`${prefix}: resolver type '${target}' must be an EDGE_TO_CLOUD type`);
        }
      }
    }
  }
}

function checkPositive(
  prefix: string,
  field: string,
  value: number | null | undefined,
  errors: string[],
): void {
  if (value != null && value <= 0) {
    errors.push(`${prefix}.${field} must be positive`);
  }
}

// ---- Catalog validation — mirrors com.proxyapp.control.CatalogValidator verbatim. ----
// Error message text must match the Java side character-for-character (vitest parity vectors
// in catalog-validate.test.ts lock this), so the UI rejects exactly what the workflow would.

const KNOWN_CODECS = new Set<string>(["json", "xml", "raw"]);

function isBlank(value: string | null | undefined): boolean {
  return value == null || value.trim() === "";
}

/** Validate one message-type entry (mirrors CatalogValidator.validateEntry). */
export function validateCatalogEntry(
  entry: CatalogEntryDto,
  knownCodecs: Set<string> = KNOWN_CODECS,
): string[] {
  const errors: string[] = [];
  const label = isBlank(entry?.type) ? "message type" : `message type ${entry.type}`;

  if (isBlank(entry?.type)) {
    errors.push("message type name must not be blank");
  }

  let edgeToCloud = false;
  if (isBlank(entry?.direction)) {
    errors.push(`${label}: direction must not be blank`);
  } else if (entry.direction !== "CLOUD_TO_EDGE" && entry.direction !== "EDGE_TO_CLOUD") {
    errors.push(
      `${label}: unknown direction '${entry.direction}' (expected CLOUD_TO_EDGE or EDGE_TO_CLOUD)`,
    );
  } else {
    edgeToCloud = entry.direction === "EDGE_TO_CLOUD";
  }

  if (isBlank(entry?.codec)) {
    errors.push(`${label}: codec must not be blank`);
  } else if (!knownCodecs.has(entry.codec)) {
    const sorted = [...knownCodecs].sort();
    errors.push(`${label}: unknown codec '${entry.codec}', available: [${sorted.join(", ")}]`);
  }

  if (edgeToCloud && isBlank(entry?.cloudEndpoint)) {
    errors.push(`${label}: EDGE_TO_CLOUD type requires a cloudEndpoint`);
  }
  return errors;
}

/** Validate a whole catalog (mirrors CatalogValidator.validateCatalog). */
export function validateCatalog(
  entries: CatalogEntryDto[],
  knownCodecs: Set<string> = KNOWN_CODECS,
): string[] {
  const errors: string[] = [];
  if (entries == null) {
    errors.push("catalog must not be null");
    return errors;
  }
  if (entries.length === 0) {
    errors.push("catalog must define at least one message type");
    return errors;
  }
  const seen = new Set<string>();
  for (const entry of entries) {
    errors.push(...validateCatalogEntry(entry, knownCodecs));
    if (entry != null && !isBlank(entry.type) && seen.has(entry.type)) {
      errors.push(`duplicate message type: ${entry.type}`);
    } else if (entry != null && !isBlank(entry.type)) {
      seen.add(entry.type);
    }
  }
  return errors;
}

function claimInbound(
  owners: Map<string, string>,
  device: EdgeConfig,
  binding: RouteBinding,
  claimant: string,
  errors: string[],
): void {
  // Inbound channels are proxy-wide resources: one channel carries exactly one type.
  const key = `${binding.transport}|${binding.channel.value}`;
  const previous = owners.get(key);
  if (previous !== undefined) {
    errors.push(
      `inbound channel collision on ${binding.transport} ${binding.channel.kind}=${binding.channel.value}: already used by ${previous}`,
    );
  } else {
    owners.set(key, `${device.deviceId}/${claimant}`);
  }
}
