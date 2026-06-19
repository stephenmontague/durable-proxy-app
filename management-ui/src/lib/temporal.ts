import { Client, Connection } from "@temporalio/client";
import type { WorkerLiveness } from "@/lib/types";

// Server-side only. The browser never talks to Temporal — these helpers back the
// Next.js API routes, which are the UI's entire control surface.

const ADDRESS = process.env.TEMPORAL_ADDRESS ?? "localhost:7233";
const NAMESPACE = process.env.TEMPORAL_NAMESPACE ?? "default";

/** Cloud app base URL — backs the config read model (H2) and the demo dispatch/confirms. */
export const DUMMY_CLOUD_URL = process.env.DUMMY_CLOUD_URL ?? "http://localhost:8091";

export const CONTROL_WORKFLOW_ID = process.env.PROXY_CONTROL_WORKFLOW_ID ?? "proxy-control";
export const DATA_TASK_QUEUE = process.env.PROXY_TASK_QUEUE ?? "proxy-main";
export const CONTROL_TASK_QUEUE = process.env.PROXY_CONTROL_TASK_QUEUE ?? "proxy-control";

let clientPromise: Promise<Client> | null = null;

export function temporalClient(): Promise<Client> {
  if (!clientPromise) {
    clientPromise = Connection.connect({ address: ADDRESS }).then(
      (connection) => new Client({ connection, namespace: NAMESPACE }),
    );
    // Allow a retry on the next request if the first connect fails.
    clientPromise.catch(() => {
      clientPromise = null;
    });
  }
  return clientPromise;
}

export function temporalNamespace(): string {
  return NAMESPACE;
}

export function controlHandle(client: Client) {
  return client.workflow.getHandle(CONTROL_WORKFLOW_ID);
}

/** protobuf Timestamp -> epoch millis (handles Long-ish seconds). */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function tsToMillis(ts: any): number | null {
  if (!ts) return null;
  const seconds = typeof ts.seconds === "object" ? Number(ts.seconds.toString()) : Number(ts.seconds ?? 0);
  const nanos = Number(ts.nanos ?? 0);
  if (!Number.isFinite(seconds)) return null;
  return seconds * 1000 + Math.floor(nanos / 1_000_000);
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function tsToIso(ts: any): string | null {
  const ms = tsToMillis(ts);
  return ms == null ? null : new Date(ms).toISOString();
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function durationToMillis(d: any): number | null {
  if (!d) return null;
  const seconds = typeof d.seconds === "object" ? Number(d.seconds.toString()) : Number(d.seconds ?? 0);
  const nanos = Number(d.nanos ?? 0);
  if (!Number.isFinite(seconds)) return null;
  return seconds * 1000 + Math.floor(nanos / 1_000_000);
}

const TASK_QUEUE_TYPE_WORKFLOW = 1;

/**
 * Infers proxy liveness from Temporal's view of task-queue pollers. Zero control-queue
 * pollers means the proxy process is down or unreachable; zero data-queue pollers with a
 * live control queue means outbound is paused (soft-disabled).
 */
export async function workerLiveness(client: Client): Promise<WorkerLiveness> {
  const svc = client.connection.workflowService;
  const describe = (name: string) =>
    svc.describeTaskQueue({
      namespace: NAMESPACE,
      taskQueue: { name },
      taskQueueType: TASK_QUEUE_TYPE_WORKFLOW,
    });

  const [control, data] = await Promise.allSettled([
    describe(CONTROL_TASK_QUEUE),
    describe(DATA_TASK_QUEUE),
  ]);

  let controlPollers = 0;
  let dataPollers = 0;
  let lastAccessAgoMs: number | null = null;

  if (control.status === "fulfilled") {
    const pollers = control.value.pollers ?? [];
    controlPollers = pollers.length;
    for (const p of pollers) {
      const ms = tsToMillis(p.lastAccessTime);
      if (ms != null) {
        const ago = Date.now() - ms;
        if (lastAccessAgoMs == null || ago < lastAccessAgoMs) lastAccessAgoMs = ago;
      }
    }
  }
  if (data.status === "fulfilled") {
    dataPollers = (data.value.pollers ?? []).length;
  }
  return { controlPollers, dataPollers, lastAccessAgoMs };
}

export function errorResponse(e: unknown, status = 502): Response {
  const message = e instanceof Error ? e.message : String(e);
  return Response.json({ error: message }, { status });
}
