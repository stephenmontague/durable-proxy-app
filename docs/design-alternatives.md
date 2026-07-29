# Design Alternatives & Adapting the Proxy

This proxy makes specific design choices, and most of them were tradeoffs rather than the only option. This document lists the significant ones so you can adapt the proxy to your own constraints — and so the reasoning is on record.

> **Design rationale of record.** This is the deepest "why it's built this way" reference for the proxy — the durable record of the tradeoffs behind the current design. For what the proxy is and how to run it, see the [README](../README.md); for how the pieces work (control plane, hot reload, per-transport reliability), see [internals.md](internals.md); this document is the layer beneath those — why each of those choices was made, and what you'd change to choose differently.

Each entry follows the same shape:

- **Decision** — what the code does today
- **Why** — the reasoning behind it
- **Alternative** — the other road
- **When you'd switch** — the conditions that make the alternative the better call
- **How here** — where in this codebase you'd make the change

Adding a message **type** or a **device** is always just configuration (an Update) — never code. The alternatives below are about the design of the proxy itself.

---

## Control plane & read model

### 1. Query the workflow vs persist a read model

- **Decision** — The cloud persists each accepted Update's returned state to a local store (the reference app uses H2) and serves UI reads from there; it queries `getState` only once, to hydrate on boot.
- **Why** — Temporal Queries are billable Actions. Serving UI reads from a local model keeps Action count off the high-frequency read path, and the read model stays available even if Temporal is briefly unreachable.
- **Alternative** — Drop the local store; the UI/API queries the control workflow's `getState` on every read, making the workflow the sole source of truth.
- **When you'd switch** — Low read volume; you want zero local state to operate; simplicity over Action cost; or you'd rather not run a datastore beside your cloud app.
- **How here** — In the reference cloud (`dummy-cloud`), remove the `StoredConfig` / `StoredConfigRepository` H2 layer and point `ConfigStateService.readState()` at `queryState()` (`getState`) instead of the repository; add a short in-memory TTL cache if reads are bursty. The proxy is unchanged — `getState` already returns the full `ProxyControlState`.

### 2. Compact Update ack vs full-state echo

- **Decision** — Each config `@UpdateMethod` returns the entire `ProxyControlState`, which the cloud persists.
- **Why** — Simplest read-model update: the cloud gets the whole new state in the same call, nothing to reconstruct.
- **Alternative** — Return a compact `{version, accepted, lastError}` and have the cloud apply its own submitted change to the read model incrementally, resyncing via `getState` only on a version gap.
- **When you'd switch** — Large configs (big device/catalog lists) where the full-state echo bloats `proxy-control` event history — it's written on every accepted change, and again as the reconcile activity input.
- **How here** — Change the config `@UpdateMethod` return types via `accept()` / `reject()` in `ProxyControlWorkflow(+Impl)` to a small record; update `ConfigStateService.applyChange` to mutate its stored state from the submitted change plus returned version, falling back to `getState` when `version != local + 1`. Keep `getState` as the full-state source.
- **Maintainer's take** — Planned here. The full-state echo is pure redundancy — the cloud already knows what it submitted — so trimming it to a compact ack is a clean, Action-free win for any install with a non-trivial config.

### 3. Signals + query vs Updates for config

- **Decision** — Config changes are Updates (validate + mutate + return synchronously).
- **Why** — One round trip yields synchronous accept/reject with a reason (`lastError`), no separate confirmation query, and validation runs before the change goes live.
- **Alternative** — Fire a signal to change config and issue a separate query to read the result (the pre-Update pattern).
- **When you'd switch** — An SDK/runtime/server without Update support, or where you specifically want fire-and-forget config with out-of-band confirmation.
- **How here** — Replace the `@UpdateMethod`s with `@SignalMethod`s that mutate state, and have callers poll `getState` for the new `version` / `lastError`. Validation still runs in-handler, but the caller learns the outcome asynchronously.
- **Maintainer's take** — Not recommended unless your runtime genuinely lacks Updates. The synchronous validate-then-accept/reject in one call is a real improvement; reverting to signals + query trades it away and reopens the race between "changed" and "confirmed."

## Reconcile

### 4. Full-state level-triggered vs delta reconcile

- **Decision** — Every reconcile ships the entire desired `ProxyControlState`; the proxy rebuilds the whole `RouteTable` and reconciles all listeners.
- **Why** — Level-triggered reconciliation (like a Kubernetes controller) is self-healing: the proxy converges to desired state regardless of what it had, so a missed change can't leave it permanently diverged.
- **Alternative** — Send only the changed entity (a delta) and apply it incrementally.
- **When you'd switch** — Very large per-site fleets where shipping full state each change bloats history or nears the ~2 MB payload limit.
- **How here** — Add per-entity versioning to `RoutingState` (today it holds one global `appliedVersion` and swaps the whole table), add a targeted reconcile activity carrying a single device, and keep full-state reconcile for boot and drift self-heal. Tradeoff: you reintroduce the divergence risk that level-triggering removes.

### 5. Single control workflow vs entity-workflow-per-device

- **Decision** — One singleton `ProxyControlWorkflow` per install holds all devices and the catalog.
- **Why** — Cross-device validation (port-pool membership, no channel collisions) happens atomically in one place; there's one snapshot of desired state and one reconcile that applies the whole install.
- **Alternative** — Model each device as its own long-lived workflow (aggregate = workflow).
- **When you'd switch** — Thousands of devices per site: per-device history stays tiny, edits touch only that device, there's no fleet-wide payload ceiling, and it's naturally sharded.
- **How here** — Split `ProxyControlWorkflow` into a per-device workflow keyed by `deviceId` plus a lightweight directory/coordinator (or list them via visibility + Search Attributes); move cross-device validation into the coordinator. Tradeoff: you lose atomic cross-device validation and take on more workflows/Actions and coordination.

## Data path

### 6. Standalone activity vs a workflow for inbound

- **Decision** — Edge→cloud delivery is a `DeliverToCloud` standalone activity, started by `InboundGateway` via `ActivityClient`.
- **Why** — Inbound is a single idempotent step (POST until 2xx) with nothing to orchestrate; a workflow wrapper would add Actions and history for no orchestration benefit.
- **Alternative** — Wrap it in a `DeliverToCloud` workflow that runs the POST activity.
- **When you'd switch** — You want first-class per-message visibility (history + Search Attributes) on the inbound path equal to the outbound path, or you foresee multi-step inbound orchestration.
- **How here** — Change `InboundGateway.enqueue` to `WorkflowClient.start` a `DeliverToCloud` workflow (Workflow ID `{type}-{businessId}`, `REJECT_DUPLICATE`) whose single activity does the POST; register the workflow on the `proxy-main` worker. The proxy is already both client and worker, so this is a small change. Cost: extra Actions and history per inbound message.
- **Maintainer's take** — Don't switch just because standalone activities are labeled Public Preview. The feature is on a stable trajectory — it's being adopted in production, not an experiment likely to be pulled — so "get onto a GA surface" isn't a real reason to take on a workflow's overhead. Switch only for the visibility or future-orchestration reasons above.

### 7. Carry payload through Temporal vs claim-check

- **Decision** — The `payload` travels inside `CanonicalMessage` through the workflow/activity, so it's recorded in event history.
- **Why** — Simplest: one self-contained message, no external store, Temporal is the only dependency.
- **Alternative** — Store large payloads externally and carry a reference/hash (claim-check). For cloud→edge specifically, reference the payload on the cloud app the proxy already reaches — no new egress.
- **When you'd switch** — Large payloads (documents, files over FTP) where per-message active/retained storage adds up, or you near the ~2 MB payload limit.
- **How here** — For cloud→edge, start `DeliverToEdge` with `{type, businessId, ref}` and have `TransmitToDevice` GET the bytes from the cloud (the same egress path `DeliverToCloud` already uses). For edge→cloud, the payload must stay durable until the cloud acks, so an external store is required — a genuine new egress dependency at the edge — so retention tuning (#9) is usually the better lever there.

### 8. No local durable spool vs a spool / HA

- **Decision** — No local durable spool. The proxy relies on Temporal's SLA plus the SDK's auto-reconnecting channel for transient unreachability, and on ack-after-enqueue so devices retry until Temporal has the message.
- **Why** — A spool duplicates what Temporal already does for cloud-unreachability; the only residual gap — the proxy host itself down — a spool can't fully fix.
- **Alternative** — Add a local durable spool, or run an HA proxy pair.
- **When you'd switch** — Strict host-down durability requirements, or environments where the edge box's disk is more reliable than its Temporal connectivity.
- **How here** — Add a durable queue in front of `InboundGateway` and the outbound send, drained on reconnect. For HA, note two proxies on one install would contend for the ingress ports/sockets — so you'd want active/standby with lease-based ownership, not active/active.

## Cost & ops

### 9. Tune retention + Cloud Export vs long in-cluster retention

- **Decision** — Not tuned in code; the install uses whatever the namespace retention period is.
- **Why** — Default and simplest.
- **Alternative** — Set namespace retention short and use Temporal Cloud Export to archive closed histories to your own bucket for long-term audit.
- **When you'd switch** — Cost-sensitive deployments, or you need long audit but not in-cluster.
- **How here** — This is a namespace/Cloud setting, not proxy code. Key constraint: retention is your **dedup window** — `REJECT_DUPLICATE` only holds while the prior execution is still in-cluster, so keep retention above the worst-case duplicate/replay gap (a device offline then resending) and above the Export cadence. Confirm Export coverage for standalone activities (the inbound path).

### 10. Search Attributes vs memo for the activity feed

- **Decision** — Outbound `DeliverToEdge` sets a `memo`; there are no custom Search Attributes.
- **Why** — Memo is zero-setup metadata visible in list results — enough for a simple feed.
- **Alternative** — Upsert typed Search Attributes (e.g. `messageType`, `direction`, `businessId`).
- **When you'd switch** — You want the UI to filter/query the feed server-side by type/direction/status rather than client-side.
- **How here** — Register custom Search Attributes on the namespace, call `Workflow.upsertTypedSearchAttributes(...)` in `DeliverToEdgeWorkflowImpl` (and the inbound workflow if you adopt #6), and query them via the visibility API. Memo is not indexed/filterable; Search Attributes are.

### 11. Namespace-per-install vs shared namespace + Task Queue Fairness

- **Decision** — One Temporal namespace per install, with per-namespace credentials.
- **Why** — Blast radius: a compromised edge box's credentials reach only that customer's own namespace and data.
- **Alternative** — A shared namespace with a task queue per tenant and Task Queue Fairness to prevent noisy-neighbor starvation.
- **When you'd switch** — Many small tenants where per-namespace overhead (provisioning, cost, management) outweighs the isolation.
- **How here** — A deployment/topology choice, not core code — the proxy already reads its namespace and task queues from config. You'd add tenant routing (task-queue-per-tenant) and enable Fairness keys, and re-examine the credential model since isolation now rests on task-queue scoping rather than namespace boundaries.
- **Maintainer's take** — For this deployment model, namespace-per-install is the right call and stays. The worker runs inside a customer's own network and must never be able to reach another customer's data, so the namespace boundary is a security control, not just overhead-avoidance. The shared-namespace alternative suits a central multi-tenant service you operate yourself — not an installed edge box; only consider it if your topology is the former.

## Evolution & extension

### 12. Additive-only vs `getVersion` / Worker Versioning

- **Decision** — The long-lived `proxy-control` workflow is evolved additively only (new methods, new optional state fields); no `getVersion` patching today.
- **Why** — Additive changes are replay-safe against the running singleton (old histories never exercised the new path), and Jackson-by-field-name serialization keeps state changes safe — so most evolution needs no versioning ceremony.
- **Alternative** — `Workflow.getVersion` patching, and/or Worker Versioning (Worker Deployments + Build IDs, `AUTO_UPGRADE` for long-runners).
- **When you'd switch** — You must change the _logic_ of an existing handler, or retype a state field, on a run that's already in flight.
- **How here** — Wrap the changed branch in `Workflow.getVersion("change-id", ...)`; for deployment-level control adopt Worker Versioning with `AUTO_UPGRADE` on the singleton plus `getVersion` for the transition. (The Java SDK doesn't auto-record `TemporalChangeVersion` — upsert it if you want to query which runs are patched.)

### 13. Extend via the SPIs

- **Decision** — Codecs, transports, and inbound type resolution are pluggable SPIs (`MessageCodec`, `Connector`, `MessageTypeResolver`); message types and devices are pure data.
- **Why** — Adding a format or protocol shouldn't require touching the core, and adding a message type or device shouldn't require code at all.
- **How to adapt** —
  - **New payload format** → implement `MessageCodec`, register in `CodecRegistry` / `ProxyAppConfig` (e.g. CSV, fixed-width).
  - **New transport** → implement `Connector` plus a matching ingress listener, wire it into `ConnectorFactory` (e.g. SFTP, MQTT).
  - **Opaque multiplexed channel** → implement `MessageTypeResolver` (the `FilenamePatternResolver` / `ContentPatternResolver` ship as examples).
- **When** — Your devices speak a wire format or protocol outside `{json, xml, raw}` × `{HTTP, TCP, FTP}`. Adding a message _type_ or _device_ stays config-only — no code.
