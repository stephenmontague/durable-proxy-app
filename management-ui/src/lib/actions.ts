"use client";

import type { ControlStateResponse } from "@/lib/types";

/** Outcome of a config change: the cloud signals the workflow and reports accept/reject synchronously. */
export interface ConfigOutcome {
  accepted: boolean;
  version?: number;
  message?: string;
}

/**
 * POST a control-plane action. Config changes route through the cloud app, which signals the
 * proxy-control workflow (the source of truth), waits for accept/reject, and persists to its read
 * model on accept — so the outcome comes back synchronously (no client-side polling). Lifecycle
 * commands (restart/shutdown) signal the workflow directly and report {@code accepted: true}.
 */
export async function postSignal(action: string, arg?: unknown): Promise<ConfigOutcome> {
  const res = await fetch("/api/control/signal", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ action, arg }),
  });
  const json = await res.json();
  if (!res.ok) throw new Error(json?.error ?? res.statusText);
  return json as ConfigOutcome;
}

/**
 * Desired config for the Config + Dispatch tabs — served from the cloud's H2 read model, so it's
 * cheap (no Temporal Query) and persists across restarts.
 */
export async function fetchConfigState(): Promise<ControlStateResponse> {
  const res = await fetch("/api/control/config", { cache: "no-store" });
  const json = await res.json();
  if (!res.ok) throw new Error(json?.error ?? res.statusText);
  return json as ControlStateResponse;
}

/** Live proxy status (applied state + liveness) for the Dashboard — read from Temporal. */
export async function fetchControlState(): Promise<ControlStateResponse> {
  const res = await fetch("/api/control/state", { cache: "no-store" });
  const json = await res.json();
  if (!res.ok) throw new Error(json?.error ?? res.statusText);
  return json as ControlStateResponse;
}
