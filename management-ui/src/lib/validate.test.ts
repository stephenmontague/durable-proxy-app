import { describe, expect, it } from "vitest";
import { validateConfig } from "./validate";
import type { Direction, EdgeConfig, TcpProtocol } from "./types";

// TCP-protocol vectors mirroring ConfigValidatorTest.java — error strings must match
// the Java validator character-for-character.

const typeDirections: Record<string, Direction> = {
  CONFIG_UPDATE: "CLOUD_TO_EDGE",
  CONFIG_ACK: "EDGE_TO_CLOUD",
  DEVICE_COMMAND: "CLOUD_TO_EDGE",
};
const pool = Array.from({ length: 11 }, (_, i) => 6000 + i);

function device(overrides: Partial<EdgeConfig>): EdgeConfig {
  return {
    deviceId: "a",
    baseUrl: null,
    host: "10.0.0.5",
    ftpPort: null,
    ftpUser: null,
    ftpPassword: null,
    bindings: [],
    ...overrides,
  };
}

describe("validateConfig tcpProtocol rules", () => {
  it("valid MLLP config passes", () => {
    const mllp: TcpProtocol = {
      startDelimiter: "<VT>",
      endDelimiter: "<FS><CR>",
      ackReply: "<VT>ACK {activityId}<FS><CR>",
      nakReply: "<VT>NAK {reason}<FS><CR>",
      expectedAck: "ACK",
      awaitReply: true,
    };
    const d = device({
      deviceId: "gateway-1",
      tcpProtocol: mllp,
      bindings: [
        {
          messageType: "CONFIG_ACK",
          transport: "TCP",
          channel: { kind: "PORT", value: "6001" },
        },
      ],
    });
    expect(validateConfig(typeDirections, pool, [d])).toEqual([]);
  });

  it("override requires TCP transport", () => {
    const d = device({
      baseUrl: "http://e",
      bindings: [
        {
          messageType: "DEVICE_COMMAND",
          transport: "HTTP",
          channel: { kind: "PATH", value: "/x" },
          tcpProtocol: { endDelimiter: "<LF>" },
        },
      ],
    });
    expect(validateConfig(typeDirections, pool, [d])).toEqual([
      "a: tcpProtocol override requires TCP transport, got HTTP",
    ]);
  });

  it("fields must parse and be non-empty", () => {
    const d = device({
      tcpProtocol: { startDelimiter: "\\x0", endDelimiter: "" },
    });
    expect(validateConfig(typeDirections, pool, [d])).toEqual([
      "a: device tcpProtocol.startDelimiter: \\x escape requires two hex digits at position 0",
      "a: device tcpProtocol.endDelimiter must not be empty",
    ]);
  });

  it("startDelimiter requires endDelimiter; end-only is legal", () => {
    expect(
      validateConfig(typeDirections, pool, [device({ tcpProtocol: { startDelimiter: "<STX>" } })]),
    ).toEqual(["a: device tcpProtocol: startDelimiter requires endDelimiter"]);
    expect(
      validateConfig(typeDirections, pool, [device({ tcpProtocol: { endDelimiter: "<LF>" } })]),
    ).toEqual([]);
  });

  it("fire-and-forget with expectedAck is contradictory", () => {
    const d = device({
      tcpProtocol: { endDelimiter: "<LF>", expectedAck: "PONG", awaitReply: false },
    });
    expect(validateConfig(typeDirections, pool, [d])).toEqual([
      "a: device tcpProtocol: expectedAck is meaningless when awaitReply is false",
    ]);
  });

  it("binding-level protocol is validated with the binding label", () => {
    const d = device({
      bindings: [
        {
          messageType: "CONFIG_ACK",
          transport: "TCP",
          channel: { kind: "PORT", value: "6001" },
          tcpProtocol: { endDelimiter: "<NOPE>" },
        },
      ],
    });
    expect(validateConfig(typeDirections, pool, [d])).toEqual([
      "a: CONFIG_ACK tcpProtocol.endDelimiter: unknown token '<NOPE>' at position 0",
    ]);
  });
});

// Persistent TCP session vectors mirroring ConfigValidatorTest.java — error strings must match
// the Java validator character-for-character.
describe("validateConfig tcpSession rules", () => {
  it("valid persistent CLIENT session passes", () => {
    const d = device({
      host: "10.0.0.5",
      tcpSession: {
        mode: "PERSISTENT",
        role: "CLIENT",
        port: 9001,
        heartbeat: {
          sendIntervalSec: 30,
          sendPayload: "<VT>PING<FS>",
          expectReply: "PONG",
          replyTimeoutMs: 5000,
          expectInboundSec: 60,
          missThreshold: 3,
        },
      },
    });
    expect(validateConfig(typeDirections, pool, [d])).toEqual([]);
  });

  it("valid persistent SERVER watchdog session passes", () => {
    const d = device({
      host: null,
      tcpSession: {
        mode: "PERSISTENT",
        role: "SERVER",
        listenPort: 6005,
        heartbeat: { expectInboundSec: 60, missThreshold: 2 },
      },
    });
    expect(validateConfig(typeDirections, pool, [d])).toEqual([]);
  });

  it("PER_MESSAGE session is not validated", () => {
    const d = device({ host: null, tcpSession: { mode: "PER_MESSAGE" } });
    expect(validateConfig(typeDirections, pool, [d])).toEqual([]);
  });

  it("persistent session requires a role", () => {
    const d = device({
      host: "10.0.0.5",
      tcpSession: {
        mode: "PERSISTENT",
        heartbeat: { sendIntervalSec: 30, sendPayload: "PING", missThreshold: 3 },
      },
    });
    expect(validateConfig(typeDirections, pool, [d])).toEqual([
      "a: tcpSession: PERSISTENT session requires a role (CLIENT or SERVER)",
    ]);
  });

  it("CLIENT session requires host and port", () => {
    const d = device({
      host: null,
      tcpSession: {
        mode: "PERSISTENT",
        role: "CLIENT",
        heartbeat: { sendIntervalSec: 30, sendPayload: "PING", missThreshold: 3 },
      },
    });
    expect(validateConfig(typeDirections, pool, [d])).toEqual([
      "a: tcpSession: CLIENT role requires the device host",
      "a: tcpSession: CLIENT role requires a port",
    ]);
  });

  it("SERVER session requires listenPort or handshakeId", () => {
    const d = device({
      tcpSession: {
        mode: "PERSISTENT",
        role: "SERVER",
        heartbeat: { expectInboundSec: 60, missThreshold: 2 },
      },
    });
    expect(validateConfig(typeDirections, pool, [d])).toEqual([
      "a: tcpSession: SERVER role requires listenPort or handshakeId",
    ]);
  });

  it("persistent session requires at least one liveness mechanism", () => {
    const d = device({
      host: "10.0.0.5",
      tcpSession: { mode: "PERSISTENT", role: "CLIENT", port: 9001 },
    });
    expect(validateConfig(typeDirections, pool, [d])).toEqual([
      "a: tcpSession: PERSISTENT session requires at least one liveness mechanism (heartbeat.sendIntervalSec or heartbeat.expectInboundSec)",
    ]);
  });

  it("heartbeat WireString fields must parse", () => {
    const d = device({
      host: "10.0.0.5",
      tcpSession: {
        mode: "PERSISTENT",
        role: "CLIENT",
        port: 9001,
        heartbeat: { sendIntervalSec: 30, sendPayload: "<NOPE>", missThreshold: 3 },
      },
    });
    expect(validateConfig(typeDirections, pool, [d])).toEqual([
      "a: tcpSession.heartbeat.sendPayload: unknown token '<NOPE>' at position 0",
    ]);
  });

  it("outbound ping requires a payload", () => {
    const d = device({
      host: "10.0.0.5",
      tcpSession: {
        mode: "PERSISTENT",
        role: "CLIENT",
        port: 9001,
        heartbeat: { sendIntervalSec: 30, missThreshold: 3 },
      },
    });
    expect(validateConfig(typeDirections, pool, [d])).toEqual([
      "a: tcpSession.heartbeat: sendIntervalSec requires sendPayload",
    ]);
  });

  it("expectReply requires an outbound ping", () => {
    const d = device({
      tcpSession: {
        mode: "PERSISTENT",
        role: "SERVER",
        listenPort: 6005,
        heartbeat: { expectReply: "PONG", expectInboundSec: 60, missThreshold: 2 },
      },
    });
    expect(validateConfig(typeDirections, pool, [d])).toEqual([
      "a: tcpSession.heartbeat: expectReply requires sendIntervalSec",
    ]);
  });

  it("heartbeat intervals must be positive", () => {
    const d = device({
      host: "10.0.0.5",
      tcpSession: {
        mode: "PERSISTENT",
        role: "CLIENT",
        port: 9001,
        heartbeat: { sendIntervalSec: 0, sendPayload: "PING", missThreshold: 3 },
      },
    });
    expect(validateConfig(typeDirections, pool, [d])).toEqual([
      "a: tcpSession.heartbeat.sendIntervalSec must be positive",
    ]);
  });

  it("valid inboundType (EDGE_TO_CLOUD) passes", () => {
    const d = device({ host: "10.0.0.5", tcpSession: persistentClient("CONFIG_ACK") });
    expect(validateConfig(typeDirections, pool, [d])).toEqual([]);
  });

  it("unknown inboundType is rejected", () => {
    const d = device({ host: "10.0.0.5", tcpSession: persistentClient("MYSTERY") });
    expect(validateConfig(typeDirections, pool, [d])).toEqual([
      "a: tcpSession: unknown inboundType 'MYSTERY'",
    ]);
  });

  it("inboundType must be EDGE_TO_CLOUD", () => {
    const d = device({ host: "10.0.0.5", tcpSession: persistentClient("DEVICE_COMMAND") });
    expect(validateConfig(typeDirections, pool, [d])).toEqual([
      "a: tcpSession: inboundType 'DEVICE_COMMAND' must be an EDGE_TO_CLOUD type",
    ]);
  });
});

function persistentClient(inboundType: string) {
  return {
    mode: "PERSISTENT" as const,
    role: "CLIENT" as const,
    port: 9001,
    heartbeat: { sendIntervalSec: 30, sendPayload: "PING", missThreshold: 3 },
    inboundType,
  };
}
