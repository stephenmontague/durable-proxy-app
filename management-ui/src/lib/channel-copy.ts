import type { Direction, Transport } from "@/lib/types";

export interface ChannelCopy {
  /** Short tag for what this channel coordinate is, e.g. "proxy ingress path". */
  role: string;
  /** One-line explanation, contrasting the device↔proxy channel with a type's cloud endpoint. */
  hint: string;
  /** Example value for the input placeholder. */
  placeholder: string;
}

const NEUTRAL = "Device↔proxy coordinate for this binding.";

/**
 * Copy for a binding's *channel* input — the device↔proxy coordinate — keyed to the transport and the
 * bound type's direction. Inbound (EDGE_TO_CLOUD) channels live on THIS proxy (the device reaches in);
 * outbound (CLOUD_TO_EDGE) channels live on the device (the proxy reaches out). Either way the channel
 * is distinct from a type's *cloud endpoint* (proxy→cloud) — the value operators most often confuse it
 * with. Direction is known at every entry point (state.typeDirections / availableTypes), so the hint
 * can name the right side; an unset direction falls back to a neutral line + the transport's example.
 */
export function channelCopy(transport: Transport, direction?: Direction): ChannelCopy {
  const inbound = direction === "EDGE_TO_CLOUD";
  const outbound = direction === "CLOUD_TO_EDGE";
  switch (transport) {
    case "HTTP":
      if (inbound)
        return {
          role: "proxy ingress path",
          hint: "Path on this proxy the device POSTs to — e.g. /command-result. Not the cloud endpoint.",
          placeholder: "/command-result",
        };
      if (outbound)
        return {
          role: "device path",
          hint: "Path on the device (appended to Base URL) the proxy delivers to — e.g. /commands.",
          placeholder: "/commands",
        };
      return { role: "channel", hint: NEUTRAL, placeholder: "/inbound-path" };
    case "TCP":
      if (inbound)
        return {
          role: "proxy port",
          hint: "Port on this proxy the device connects to (must sit in the site pool).",
          placeholder: "6001",
        };
      if (outbound)
        return {
          role: "device port",
          hint: "Port on the device the proxy connects to.",
          placeholder: "6001",
        };
      return { role: "channel", hint: NEUTRAL, placeholder: "6001" };
    case "FTP":
      if (inbound)
        return {
          role: "proxy folder",
          hint: "Folder on this proxy's FTP server the device drops files into.",
          placeholder: "inbound",
        };
      if (outbound)
        return {
          role: "device folder",
          hint: "Folder on the device's FTP server the proxy uploads to.",
          placeholder: "outbound",
        };
      return { role: "channel", hint: NEUTRAL, placeholder: "folder-name" };
    default:
      return { role: "channel", hint: NEUTRAL, placeholder: "" };
  }
}
