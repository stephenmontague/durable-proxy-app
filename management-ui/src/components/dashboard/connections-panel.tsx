"use client";

import { Led } from "@/components/ui-custom/led";
import { Panel } from "@/components/ui-custom/panel";
import { formatClock } from "@/lib/format";
import type { LedState } from "@/lib/format";
import type { DeviceSessionStatus } from "@/lib/types";

function sessionLed(state: string): LedState {
  switch (state) {
    case "UP":
      return "ok";
    case "CONNECTING":
      return "busy";
    default:
      return "err"; // DOWN
  }
}

/**
 * The connection table: per-device persistent-link health the proxy reports back via
 * AppliedStatus.sessions. Heartbeats stay out of Temporal, so this reflects the last
 * UP/DOWN/CONNECTING transition the proxy signaled, not every beat.
 */
export function ConnectionsPanel({ sessions }: { sessions: DeviceSessionStatus[] }) {
  return (
    <Panel legend="Persistent connections">
      <table className="w-full border-collapse">
        <thead>
          <tr className="border-b border-ink/60 text-left">
            {["Device", "Role", "Link", "Last heartbeat", "In-flight"].map((h) => (
              <th
                key={h}
                className="readout pb-1 pr-2 text-[9px] font-semibold uppercase tracking-[0.14em] text-ink-faint"
              >
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {sessions.map((s) => (
            <tr key={s.deviceId} className="border-b border-hairline/70">
              <td className="readout py-1.5 pr-2 text-[11px] font-medium">{s.deviceId}</td>
              <td className="readout py-1.5 pr-2 text-[10px] text-ink-soft">{s.role}</td>
              <td className="py-1.5 pr-2">
                <span className="inline-flex items-center gap-2">
                  <Led state={sessionLed(s.state)} />
                  <span className="readout text-[10px] uppercase tracking-[0.12em]">{s.state}</span>
                </span>
              </td>
              <td className="readout py-1.5 pr-2 text-[11px] text-ink-soft">
                {formatClock(s.lastHeartbeatAt)}
              </td>
              <td className="readout py-1.5 text-[11px] text-ink-soft">{s.inflight}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Panel>
  );
}
