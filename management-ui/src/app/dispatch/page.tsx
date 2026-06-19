"use client";

// Catalog-driven dispatch: one card per CLOUD_TO_EDGE type the operator actually configured (read
// from the cloud's H2 read model), with a per-codec, editable payload. Firing one starts a real
// DeliverToEdge workflow through the cloud → Temporal → proxy → device. Re-use a business id to
// demo idempotent dedup (same id → one execution).

import { useState } from "react";
import { toast } from "sonner";
import { FeedTable } from "@/components/feed/feed-table";
import { Panel } from "@/components/ui-custom/panel";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { usePoll } from "@/hooks/use-poll";
import { samplePayload } from "@/lib/sample-payload";
import type { CatalogEntryDto, ControlStateResponse, FeedItem } from "@/lib/types";

interface Confirm {
  messageType: string;
  businessId: string;
  payload: string;
}

function freshId(type: string): string {
  const prefix = type.replace(/[^A-Za-z0-9]/g, "").slice(0, 3).toUpperCase() || "MSG";
  return `${prefix}-${Math.floor(1000 + Math.random() * 9000)}`;
}

function freshForm(entry: CatalogEntryDto): { businessId: string; payload: string } {
  const businessId = freshId(entry.type);
  return { businessId, payload: samplePayload(entry, businessId) };
}

function DispatchCard({ entry }: { entry: CatalogEntryDto }) {
  const [{ businessId, payload }, setForm] = useState(() => freshForm(entry));
  const [busy, setBusy] = useState(false);

  const dispatch = async () => {
    setBusy(true);
    try {
      const res = await fetch("/api/demo/dispatch", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ messageType: entry.type, businessId, payload }),
      });
      const json = await res.json();
      if (!res.ok) throw new Error(json?.error ?? res.statusText);
      if (json.duplicate) {
        toast.warning(`Duplicate collapsed: ${json.workflowId}`, {
          description: "Same business id — Temporal deduped it to the one existing execution.",
        });
      } else {
        toast.success(`Dispatched ${json.workflowId}`, {
          description: "Riding a DeliverToEdge workflow to the device over its configured channel.",
        });
        setForm(freshForm(entry)); // fresh id + payload for the next send
      }
    } catch (e) {
      toast.error("Dispatch failed", { description: e instanceof Error ? e.message : String(e) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel legend={`${entry.type} · ${entry.codec}`}>
      <div className="flex flex-col gap-3">
        <div className="flex flex-col gap-1.5">
          <Label className="text-[10px] font-semibold uppercase tracking-[0.14em] text-ink-soft">
            Business id
          </Label>
          <Input
            className="readout"
            value={businessId}
            onChange={(e) => setForm((f) => ({ ...f, businessId: e.target.value }))}
          />
          <span className="text-[10px] text-ink-faint">re-use an id to demo idempotent dedup</span>
        </div>
        <div className="flex flex-col gap-1.5">
          <Label className="text-[10px] font-semibold uppercase tracking-[0.14em] text-ink-soft">
            Payload ({entry.codec})
          </Label>
          <Textarea
            className="readout min-h-[88px] text-[11px] leading-relaxed"
            value={payload}
            onChange={(e) => setForm((f) => ({ ...f, payload: e.target.value }))}
          />
        </div>
        <Button
          className="btn-hard w-full font-mono text-[11px] uppercase tracking-[0.14em]"
          disabled={busy || !businessId.trim()}
          onClick={dispatch}
        >
          {busy ? "Dispatching…" : "Dispatch"}
        </Button>
      </div>
    </Panel>
  );
}

export default function DispatchPage() {
  // Catalog comes from the cloud's H2 read model (cheap); the feed/confirms stay as-is.
  const config = usePoll<ControlStateResponse>("/api/control/config", 4000);
  const confirms = usePoll<{ confirms: Confirm[] }>("/api/demo/confirms", 3000);
  const feed = usePoll<{ items: FeedItem[] }>("/api/temporal/feed", 3500);

  const dispatchable = (config.data?.state.catalogEntries ?? []).filter(
    (e) => e.direction === "CLOUD_TO_EDGE",
  );

  return (
    <div className="flex flex-col gap-7">
      {dispatchable.length === 0 ? (
        <Panel legend="Dispatch">
          <p className="readout py-6 text-center text-[11px] text-ink-faint">
            {config.error
              ? `cannot load catalog: ${config.error}`
              : "no dispatchable (CLOUD → EDGE) types in this install — define one on the Config tab."}
          </p>
        </Panel>
      ) : (
        <div className="grid gap-7 md:grid-cols-3">
          {dispatchable.map((entry) => (
            <DispatchCard key={entry.type} entry={entry} />
          ))}
        </div>
      )}

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
              none yet — dispatch something, or the device pushes its telemetry here
            </p>
          ) : (
            <ol className="flex flex-col gap-1">
              {(confirms.data?.confirms ?? [])
                .slice()
                .reverse()
                .slice(0, 12)
                .map((c, i) => (
                  <li
                    key={i}
                    className="readout flex items-baseline gap-3 border-b border-hairline/60 pb-1 text-[11px]"
                  >
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
