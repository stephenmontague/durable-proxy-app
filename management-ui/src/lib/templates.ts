import type { ChannelKind, EdgeConfig, RouteBinding, Transport } from "@/lib/types";

// Mirrors the proxy's built-in device templates (com.proxyapp.profile.DeviceFleetProfile).
// A template pre-fills message types, transports, and channel layout; the wizard only
// asks for site values. PORT channels compute as basePort + portOffset, but every
// computed channel stays editable before review.

export interface TemplateBinding {
  messageType: string;
  transport: Transport;
  kind: ChannelKind;
  value: string | null;
  portOffset: number;
}

export interface DeviceTemplateDef {
  id: string;
  name: string;
  description: string;
  bindings: TemplateBinding[];
}

export const DEVICE_TEMPLATES: DeviceTemplateDef[] = [
  {
    id: "device-fleet-standard",
    name: "Standard edge gateway",
    description:
      "A typical edge gateway: commands over HTTP, config pushes over raw TCP, batch reports via FTP folders.",
    bindings: [
      { messageType: "DEVICE_COMMAND", transport: "HTTP", kind: "PATH", value: "/commands", portOffset: 0 },
      { messageType: "COMMAND_RESULT", transport: "HTTP", kind: "PATH", value: "/command-result", portOffset: 0 },
      { messageType: "CONFIG_UPDATE", transport: "TCP", kind: "PORT", value: null, portOffset: 0 },
      { messageType: "CONFIG_ACK", transport: "TCP", kind: "PORT", value: null, portOffset: 1 },
      { messageType: "REPORT_REQUEST", transport: "FTP", kind: "FOLDER", value: "report-requests", portOffset: 0 },
      { messageType: "REPORT_UPLOAD", transport: "FTP", kind: "FOLDER", value: "report-uploads", portOffset: 0 },
    ],
  },
];

export interface SiteValues {
  deviceId: string;
  baseUrl: string;
  host: string;
  basePort: number;
  ftpPort: number | null;
  ftpUser: string;
  ftpPassword: string;
}

export function materialize(template: DeviceTemplateDef, site: SiteValues): EdgeConfig {
  const bindings: RouteBinding[] = template.bindings.map((b) => ({
    messageType: b.messageType,
    transport: b.transport,
    channel: {
      kind: b.kind,
      value: b.kind === "PORT" ? String(site.basePort + b.portOffset) : (b.value ?? ""),
    },
    resolver: null,
  }));
  const usesFtp = template.bindings.some((b) => b.transport === "FTP");
  return {
    deviceId: site.deviceId,
    baseUrl: site.baseUrl || null,
    host: site.host || null,
    ftpPort: usesFtp ? site.ftpPort : null,
    ftpUser: usesFtp && site.ftpUser ? site.ftpUser : null,
    ftpPassword: usesFtp && site.ftpPassword ? site.ftpPassword : null,
    bindings,
  };
}
