"use client";

// Demo drivers: fire real messages through the real path (cloud app -> Temporal ->
// proxy -> edge device) and watch the paired confirm come back. Business IDs are
// pre-randomized so repeat dispatches don't collide with the dedup policy — dispatching
// the SAME id twice is the idempotency demo (duplicate: true, one execution).

import { useState } from "react";
import { toast } from "sonner";
import { FeedTable } from "@/components/feed/feed-table";
import { Panel } from "@/components/ui-custom/panel";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { usePoll } from "@/hooks/use-poll";
import type { FeedItem } from "@/lib/types";

interface Confirm {
  messageType: string;
  businessId: string;
  payload: string;
}

interface DispatchDef {
  kind: string;
  title: string;
  messageType: string;
  confirm: string;
  transport: string;
  idField: string;
  idPrefix: string;
  buildPayload: (id: string) => Record<string, unknown>;
}

const DISPATCHES: DispatchDef[] = [
  {
    kind: "command",
    title: "Send command",
    messageType: "DEVICE_COMMAND",
    confirm: "COMMAND_RESULT",
    transport: "HTTP",
    idField: "commandId",
    idPrefix: "CMD",
    buildPayload: (id) => ({ commandId: id, action: "REBOOT" }),
  },
  {
    kind: "config",
    title: "Push config",
    messageType: "CONFIG_UPDATE",
    confirm: "CONFIG_ACK",
    transport: "TCP",
    idField: "configId",
    idPrefix: "CFG",
    buildPayload: (id) => ({ configId: id, key: "reportingIntervalSec", value: 30 }),
  },
  {
    kind: "report",
    title: "Request report",
    messageType: "REPORT_REQUEST",
    confirm: "REPORT_UPLOAD",
    transport: "FTP",
    idField: "reportId",
    idPrefix: "RPT",
    buildPayload: (id) => ({ reportId: id, kind: "daily-metrics" }),
  },
];

function freshId(prefix: string): string {
  return `${prefix}-${Math.floor(1000 + Math.random() * 9000)}`;
}

function DispatchCard({ def }: { def: DispatchDef }) {
  const [id, setId] = useState(() => freshId(def.idPrefix));
  const [busy, setBusy] = useState(false);

  const dispatch = async () => {
    setBusy(true);
    try {
      const res = await fetch("/api/demo/dispatch", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ kind: def.kind, payload: def.buildPayload(id) }),
      });
      const json = await res.json();
      if (!res.ok) throw new Error(json?.error ?? res.statusText);
      if (json.duplicate) {
        toast.warning(`Duplicate collapsed: ${json.workflowId ?? json.activityId}`, {
          description: "Same business ID — Temporal deduped it to the one existing execution.",
        });
      } else {
        toast.success(`Dispatched ${json.workflowId ?? json.activityId}`, {
          description: `Riding a DeliverToEdge workflow over ${def.transport}; expect ${def.confirm} back shortly.`,
        });
        setId(freshId(def.idPrefix));
      }
    } catch (e) {
      toast.error("Dispatch failed", { description: e instanceof Error ? e.message : String(e) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel legend={`${def.messageType} · ${def.transport}`}>
      <div className="flex flex-col gap-3">
        <p className="text-[12px] leading-snug text-ink-soft">
          {def.title} → edge replies with <span className="readout">{def.confirm}</span>
        </p>
        <div className="flex flex-col gap-1.5">
          <Label className="text-[10px] font-semibold uppercase tracking-[0.14em] text-ink-soft">
            {def.idField}
          </Label>
          <Input className="readout" value={id} onChange={(e) => setId(e.target.value)} />
          <span className="text-[10px] text-ink-faint">
            re-use an ID to demo idempotent dedup
          </span>
        </div>
        <pre className="readout overflow-x-auto border border-hairline bg-panel-sunken px-2 py-1.5 text-[10px] leading-relaxed">
          {JSON.stringify(def.buildPayload(id), null, 1)}
        </pre>
        <Button className="btn-hard w-full font-mono text-[11px] uppercase tracking-[0.14em]" disabled={busy || !id.trim()} onClick={dispatch}>
          {busy ? "Dispatching…" : "Dispatch"}
        </Button>
      </div>
    </Panel>
  );
}

export default function DispatchPage() {
  const confirms = usePoll<{ confirms: Confirm[] }>("/api/demo/confirms", 3000);
  const feed = usePoll<{ items: FeedItem[] }>("/api/temporal/feed", 3500);

  return (
    <div className="flex flex-col gap-7">
      <div className="grid gap-7 md:grid-cols-3">
        {DISPATCHES.map((def) => (
          <DispatchCard key={def.kind} def={def} />
        ))}
      </div>

      <div className="grid gap-7 md:grid-cols-2">
        <Panel legend="Live traffic">
          <FeedTable items={(feed.data?.items ?? []).slice(0, 10)} compact />
        </Panel>
        <Panel legend="Confirms received by cloud">
          {confirms.error ? (
            <p className="readout py-4 text-center text-[11px] text-err">
              dummy-cloud unreachable: {confirms.error}
            </p>
          ) : (confirms.data?.confirms ?? []).length === 0 ? (
            <p className="readout py-4 text-center text-[11px] text-ink-faint">
              none yet — dispatch something and the paired confirm lands here
            </p>
          ) : (
            <ol className="flex flex-col gap-1">
              {(confirms.data?.confirms ?? [])
                .slice()
                .reverse()
                .slice(0, 12)
                .map((c, i) => (
                  <li key={i} className="readout flex items-baseline gap-3 border-b border-hairline/60 pb-1 text-[11px]">
                    <span className="font-semibold">{c.messageType}</span>
                    <span className="text-ink-soft">{c.businessId}</span>
                    <span className="truncate text-ink-faint">{c.payload}</span>
                  </li>
                ))}
            </ol>
          )}
        </Panel>
      </div>
    </div>
  );
}
