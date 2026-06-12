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
