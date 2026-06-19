import { describe, expect, it } from "vitest";
import { channelCopy } from "./channel-copy";
import type { Transport } from "./types";

const TRANSPORTS: Transport[] = ["HTTP", "TCP", "FTP"];

describe("channelCopy", () => {
  it("HTTP inbound points at this proxy with a /command-result example", () => {
    const c = channelCopy("HTTP", "EDGE_TO_CLOUD");
    expect(c.role).toBe("proxy ingress path");
    expect(c.placeholder).toBe("/command-result");
    expect(c.hint).toContain("this proxy");
  });

  it("HTTP outbound points at the device with a /commands example", () => {
    const c = channelCopy("HTTP", "CLOUD_TO_EDGE");
    expect(c.role).toBe("device path");
    expect(c.placeholder).toBe("/commands");
    expect(c.hint).toContain("the device");
  });

  it("inbound hints say 'this proxy' and outbound say 'the device' for every transport", () => {
    for (const t of TRANSPORTS) {
      expect(channelCopy(t, "EDGE_TO_CLOUD").hint).toContain("this proxy");
      expect(channelCopy(t, "CLOUD_TO_EDGE").hint).toContain("the device");
    }
  });

  it("TCP uses a port placeholder in both directions", () => {
    expect(channelCopy("TCP", "EDGE_TO_CLOUD").placeholder).toBe("6001");
    expect(channelCopy("TCP", "CLOUD_TO_EDGE").placeholder).toBe("6001");
  });

  it("FTP uses inbound/outbound folder placeholders", () => {
    expect(channelCopy("FTP", "EDGE_TO_CLOUD").placeholder).toBe("inbound");
    expect(channelCopy("FTP", "CLOUD_TO_EDGE").placeholder).toBe("outbound");
  });

  it("unknown direction falls back to a neutral hint + the transport's example", () => {
    const c = channelCopy("HTTP");
    expect(c.role).toBe("channel");
    expect(c.hint).toContain("Device↔proxy");
    expect(c.placeholder).toBe("/inbound-path");
  });
});
