"use client";

import { FlowDiagram } from "@/components/flow-diagram";
import { ConnectionsPanel } from "@/components/dashboard/connections-panel";
import { ControlsPanel } from "@/components/dashboard/controls-panel";
import { ListenersPanel } from "@/components/dashboard/listeners-panel";
import { StatusPanel } from "@/components/dashboard/status-panel";
import { FeedTable } from "@/components/feed/feed-table";
import { Panel } from "@/components/ui-custom/panel";
import { usePoll } from "@/hooks/use-poll";
import type { ControlStateResponse, FeedItem } from "@/lib/types";

export default function ConsolePage() {
  // Live proxy status (applied state + liveness) from Temporal. Slower cadence keeps the per-poll
  // getState Query (a billable Action) modest; config editing lives on the Config tab (H2-backed).
  const control = usePoll<ControlStateResponse>("/api/control/state", 5000);
  const feed = usePoll<{ items: FeedItem[] }>("/api/temporal/feed", 4000);
  const cloud = usePoll<{ confirms: unknown[] }>("/api/demo/confirms", 10000);

  const state = control.data?.state;
  const liveness = control.data?.liveness;
  // Idle long-polls touch the server roughly once a minute, so anything under
  // ~2 minutes still counts as alive.
  const proxyUp =
    (liveness?.controlPollers ?? 0) > 0 &&
    (liveness?.lastAccessAgoMs == null || liveness.lastAccessAgoMs < 120_000);

  return (
    <div className="flex flex-col gap-7">
      <Panel legend="Data path">
        {control.error && !control.data ? (
          <p className="readout py-6 text-center text-[12px] text-err">
            cannot reach Temporal: {control.error}
          </p>
        ) : (
          <FlowDiagram
            cloudUp={cloud.error === null && cloud.data !== null}
            proxyUp={proxyUp}
            enabled={state?.enabled ?? false}
            restartPending={(state?.lifecycleCommand ?? "NONE") !== "NONE"}
          />
        )}
      </Panel>

      <div className="grid gap-7 md:grid-cols-3">
        {control.data && <StatusPanel data={control.data} />}
        {state && <ControlsPanel state={state} onActed={control.refresh} />}
        {state && <ListenersPanel state={state} />}
      </div>

      {state?.applied?.sessions && state.applied.sessions.length > 0 && (
        <ConnectionsPanel sessions={state.applied.sessions} />
      )}

      <Panel legend="Recent traffic">
        <FeedTable items={(feed.data?.items ?? []).slice(0, 8)} compact />
      </Panel>
    </div>
  );
}
