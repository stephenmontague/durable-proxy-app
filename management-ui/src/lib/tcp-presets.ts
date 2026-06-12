import type { TcpProtocol } from "@/lib/types";

// Common TCP wire protocols, selectable in the device wizard. "Default" (null) keeps
// the legacy behavior: EOF framing, "ACK {id}\n"/"ERR ..." replies, startsWith("ACK").

export interface TcpPreset {
  id: string;
  name: string;
  description: string;
  protocol: TcpProtocol | null;
}

export const TCP_PRESETS: TcpPreset[] = [
  {
    id: "default",
    name: "Default (EOF + ACK/ERR)",
    description: "Device half-closes to end a message; plain ACK/ERR replies.",
    protocol: null,
  },
  {
    id: "mllp",
    name: "MLLP (HL7-style)",
    description: "Frames wrapped in <VT> … <FS><CR>; persistent connections; framed acks.",
    protocol: {
      startDelimiter: "<VT>",
      endDelimiter: "<FS><CR>",
      ackReply: "<VT>ACK {activityId}<FS><CR>",
      nakReply: "<VT>NAK {reason}<FS><CR>",
      expectedAck: "ACK",
      awaitReply: true,
    },
  },
  {
    id: "newline",
    name: "Newline-delimited",
    description: "One message per line; line-based acks.",
    protocol: {
      startDelimiter: null,
      endDelimiter: "<LF>",
      ackReply: "ACK {activityId}<LF>",
      nakReply: "ERR {reason}<LF>",
      expectedAck: "ACK",
      awaitReply: true,
    },
  },
  {
    id: "fire-and-forget",
    name: "Fire-and-forget",
    description:
      "Framed send with no reply expected — for devices that never respond (e.g. printers). Delivery guarantee weakens to \"TCP write accepted\".",
    protocol: {
      startDelimiter: "<STX>",
      endDelimiter: "<ETX>",
      ackReply: null,
      nakReply: null,
      expectedAck: null,
      awaitReply: false,
    },
  },
  {
    id: "custom",
    name: "Custom…",
    description: "Start from a blank slate and set every field yourself.",
    protocol: {
      startDelimiter: null,
      endDelimiter: null,
      ackReply: null,
      nakReply: null,
      expectedAck: null,
      awaitReply: true,
    },
  },
];

/** Identify which preset a stored protocol corresponds to (for edit mode). */
export function matchPreset(protocol: TcpProtocol | null | undefined): string {
  if (protocol == null) return "default";
  for (const preset of TCP_PRESETS) {
    if (preset.protocol != null && protocolEquals(preset.protocol, protocol)) {
      return preset.id;
    }
  }
  return "custom";
}

/** One-line human summary for the review step. */
export function summarizeProtocol(p: TcpProtocol | null | undefined): string {
  if (p == null) return "legacy — EOF framing, plain ACK/ERR";
  const parts: string[] = [];
  if (p.endDelimiter != null) {
    parts.push(`framed ${p.startDelimiter ?? ""}…${p.endDelimiter}`);
  } else {
    parts.push("EOF framing");
  }
  if (p.awaitReply === false) {
    parts.push("fire-and-forget");
  } else {
    parts.push(`expects "${p.expectedAck ?? "ACK"}"`);
  }
  if (p.ackReply != null || p.nakReply != null) {
    parts.push("custom ack/nak templates");
  }
  return parts.join(" · ");
}

function protocolEquals(a: TcpProtocol, b: TcpProtocol): boolean {
  const norm = (v: string | null | undefined) => v ?? null;
  return (
    norm(a.startDelimiter) === norm(b.startDelimiter) &&
    norm(a.endDelimiter) === norm(b.endDelimiter) &&
    norm(a.ackReply) === norm(b.ackReply) &&
    norm(a.nakReply) === norm(b.nakReply) &&
    norm(a.expectedAck) === norm(b.expectedAck) &&
    (a.awaitReply ?? true) === (b.awaitReply ?? true)
  );
}
