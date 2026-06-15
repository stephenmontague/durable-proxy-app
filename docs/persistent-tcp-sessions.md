# Design: Persistent TCP Sessions + Heartbeats (multi-device)

> **Status:** implemented (phases 1–6) across the proxy, the management UI, and the `dummy-edge`
> demo. This remains the design of record; the code lives in `com.proxyapp.session`
> (`TcpSessionManager`, `DeviceSession`) + `TcpSession` config, with the connection table in the UI.
> Try it: `just run-dummy-edge-persistent` then `just demo-config-persistent`. SERVER role supports
> both a per-device listen port and **shared listen ports with handshake demux** (the
> `TcpSessionManager` reads each device's newline-terminated handshake id on connect). Unsolicited
> inbound is typed either by a single `inboundType` or, for a socket carrying several types, by a
> content `MessageTypeResolver` (kind `content-pattern`). All paths are wired end-to-end.

## Context

The proxy treats TCP as **per-message** today ([`TcpConnector`](../proxy/src/main/java/com/proxyapp/connector/TcpConnector.java): connect → write → await ack → close) — ideal for message/batch integration over unreliable links. But real-time and industrial links (PLCs, controllers, scanners; FIX/MQTT/MLLP-style protocols) require a **maintained socket kept alive with heartbeats** and fast dead-link detection. This design adds a per-device **persistent-session** mode that keeps the link warm while **Temporal still provides durable, exactly-once-ish delivery on top**.

- Supports **both initiation roles** — proxy-as-client (proxy dials the device) and proxy-as-server (device dials in) — chosen per device.
- **Configurable, bidirectional heartbeats** (proxy ping and/or device watchdog, any combination).
- **Multiple devices → multiple maintained connections**, configured and observed from the UI as a **connection table**.

This is the second of the two standard device-integration paradigms. The proxy already does the first (durable message router); this adds the **session-gateway** shape, without replacing per-message mode.

## Core principle: separate "owning the link" from "delivering a message"

- **Session layer** (long-lived, in the proxy process) owns the socket(s), heartbeats, reconnect, and liveness. **Heartbeats never enter Temporal** — they're ephemeral, high-frequency link-keepalive, not message delivery.
- **Temporal layer**: each message is still a durable `DeliverToEdge` workflow → `TransmitToDevice` activity. The activity does *not* open a socket — it hands the payload to the session and awaits the device's correlated ack.

## New components (proxy)

### `TcpSessionManager` (SmartLifecycle bean, mirrors `TcpSocketServer`/`FtpIngressListener`)
- Holds `Map<String deviceId, DeviceSession>` — **this is the connection table**.
- `reconcile(Collection<DeviceSessionConfig>)` — opens new sessions, closes removed ones, hot-updates changed ones (the same lifecycle hook the `Reconciler` already calls on `tcpSocketServer`/`ftpIngressListener`).
- `send(deviceId, MessageType, byte[]) → ack/Future` — called by the outbound activity.
- `statuses() → List<DeviceSessionStatus>` — feeds `AppliedStatus`.

### `DeviceSession` (one per device) — owns one persistent connection
- **CLIENT role**: dials the device `host:port`; reconnects with backoff on drop.
- **SERVER role**: the device dials in; the proxy holds the accepted socket. Bind socket↔device by a **per-device listen port** (unambiguous, recommended) or an identifying **handshake/first-frame** (port economy). Reuses the existing [`TcpSocketServer`](../proxy/src/main/java/com/proxyapp/ingress/TcpSocketServer.java) acceptor pattern.
- **Heartbeat engine** (configurable, any combo): optional **outbound ping** (send `sendPayload` every `sendIntervalSec`, optionally require `expectReply` within `replyTimeoutMs`) and/or **inbound watchdog** (expect a device frame at least every `expectInboundSec`); `missThreshold` consecutive misses → DOWN.
- **Read loop** demultiplexes inbound frames into three kinds: heartbeat (handled locally), **command responses** (matched to a pending send via correlation), and **unsolicited device→cloud messages** (→ start `DeliverToCloud`, exactly like today's inbound).
- Framing reuses [`TcpProtocol`](../proxy/src/main/java/com/proxyapp/routing/TcpProtocol.java) (start/end delimiters) + `WireString`. State: CONNECTING / UP / DOWN, `lastHeartbeatAt`, in-flight count, reconnect attempts.

### Correlation (request/response over one shared socket)
Configurable strategy — correlation-id echo, sequence number, or default **single-in-flight + contains-`expectedAck`** for simple devices. Needed so a send's activity gets *its* reply. Protocol-dependent; document per device.

### Inbound type resolution
One socket carries many types, so "channel = type" (port/path) no longer applies. Reuse the existing opt-in [`MessageTypeResolver`](../proxy/src/main/java/com/proxyapp/routing/MessageTypeResolver.java) SPI (header/tag/content rule) to map each inbound frame → `MessageType`, then `DeliverToCloud` as today. (This is exactly what that SPI was built for.)

## Outbound delivery path (Temporal — used by BOTH modes)

**Every outbound message still rides a durable `DeliverToEdge` workflow → `TransmitToDevice` activity, in both modes.** Durability, queuing, retry/backoff, offline-tolerance, and exactly-once-ish dedup are identical regardless of mode. The *only* difference is the transport call **inside** the activity:

- **PER_MESSAGE:** activity → `connectorFactory.require(TCP).send(...)` (fresh socket per send, as today).
- **PERSISTENT:** activity → `sessionManager.send(deviceId, type, payload)` (write onto the already-open, heartbeated socket; await the correlated ack).

In both, activity completion = durable proof of delivery; failure/timeout → Temporal retry. For PERSISTENT, if the session is **DOWN** the activity retries with backoff and delivers when the link reconnects — the command waits durably in Temporal meanwhile (Temporal matters *more* here, not less). The persistent socket is owned by the long-lived `TcpSessionManager` *outside* any activity; the activity merely uses it as its transport.

## Config model (control-workflow state, UI-editable)

Extend [`EdgeConfig`](../proxy/src/main/java/com/proxyapp/routing/EdgeConfig.java) with an optional **`TcpSession`** record (per device — the socket is per device):

- `mode`: `PER_MESSAGE` (default, today's behavior) | `PERSISTENT`
- `role`: `CLIENT` (proxy dials `host:port`) | `SERVER` (device dials in; `listenPort` or handshake id)
- heartbeat: `sendIntervalSec`, `sendPayload` (WireString), `expectReply` (WireString), `replyTimeoutMs`, `expectInboundSec`, `missThreshold`
- `correlation`: strategy + field/delimiter
- framing: reuse the device/binding `tcpProtocol`

Validation (`CatalogValidator`-style, **mirrored in `validate.ts`** byte-for-byte): CLIENT requires `host`+port; SERVER requires `listenPort` or a handshake id; all WireString fields must parse; a PERSISTENT session must define at least one liveness mechanism (outbound ping or inbound watchdog).

## Control plane, reconcile, liveness

- `Reconciler.apply()`: add `tcpSessionManager.reconcile(persistentDevices)` next to the existing `tcpSocketServer`/`ftpIngressListener` reconcile calls; disabled state → close all sessions.
- [`AppliedStatus`](../proxy/src/main/java/com/proxyapp/control/AppliedStatus.java): add `List<DeviceSessionStatus> sessions` (deviceId, role, state, `lastHeartbeatAt`, inflight), reported via the existing `reportApplied` signal — per-device link health reaches the UI over the same egress path, no new inbound ports.

## UI — the connection table

- **Config tab**: each device gains a **Connection** section — `mode` (per-message/persistent); if persistent: role, endpoint, heartbeat settings, correlation. The device list *is* the connection/routing table.
- **Live status** per device from `AppliedStatus.sessions` — an UP/DOWN/CONNECTING lamp + last-heartbeat, like the existing proxy-liveness indicator but per device. Optional dedicated "Connections" panel summarizing all live sockets.

## Socket affinity

A session's socket lives in **one** proxy process, so the send activity must run on the worker that holds it. With the **one-proxy-per-install** model this is automatic. For multiple proxy workers, pin each device's outbound work to a **device-specific task queue**. Documented now; not required for single-proxy.

## Crash / restart recovery

The session manager is **rebuilt from durable inputs on restart, not persisted** — its state splits three ways:

- **Desired/config state** (which devices, endpoints, heartbeat settings): already durable in `ProxyControlWorkflow`; the poller + `Reconciler` rebuild the session set from it on startup, exactly as listeners/routes/catalog are rebuilt today. No new storage.
- **Live runtime state** (open sockets, TCP sequence numbers, heartbeat timers, in-flight correlation map): **inherently ephemeral — cannot be serialized/rehydrated** into Temporal or anywhere. A socket is kernel/process state; on death the peer sees a RST. Recovery = **reconnect** (the `DeviceSession` backoff path); heartbeats resume; liveness re-reported.
- **In-flight message deliveries**: already durable as their own `DeliverToEdge` workflows/activities. A crash mid-send → Temporal redelivers the activity on the restarted worker → it hands the payload to the freshly-reconnected session. Messages survive via Temporal; the socket is re-established; nothing is lost.

So **don't store session contents in a workflow** — the socket part isn't storable, and the durable parts (config + in-flight messages) are already in Temporal. Restart recovery = the same reconcile-from-control-state loop the proxy already uses.

**Temporal constraint:** a workflow can't hold a socket or do I/O (determinism), so the session manager can never *be* a workflow. *Optional enhancement:* a long-running per-device **session-supervisor workflow** could record liveness transitions (UP/DOWN), reconnect counts, and richer desired state for observability/alerting — a metadata record, not the connection (the socket stays in worker-process code). Even then, heartbeats never become workflow events; at most a DOWN/UP transition is signaled. Not required for correctness.

## Guarantees / non-goals

- Heartbeats stay out of Temporal.
- Delivery is still **at-least-once** (crash mid-send → activity retry → possible re-send) — the device/cloud must tolerate duplicates. Inbound dedup is a per-type catalog knob, `allowDuplicates`: **off** (default) collapses byte-identical pushes to one delivery via the `{type}-{businessId}` activity id; **on** gives every push a unique activity id so each is delivered (event/telemetry streams where two identical frames are two real observations). The persistent socket changes transport mechanics, not the delivery guarantee.
- PER_MESSAGE TCP devices are fully unaffected.

## Phased implementation

1. `EdgeConfig.TcpSession` model + validation (Java + TS mirror).
2. `TcpSessionManager` + `DeviceSession` **CLIENT** mode: heartbeat engine, reconnect, liveness; `Reconciler` wiring; `AppliedStatus.sessions`.
3. Outbound activity hands off to the session; default single-in-flight correlation.
4. Inbound read loop → `MessageTypeResolver` → `DeliverToCloud`.
5. **SERVER** role.
6. UI: per-device connection config + live status; `dummy-edge` persistent-session profile; e2e demo.

## Verification (when built)

- **Unit**: `DeviceSession` heartbeat/reconnect/correlation against a stub `ServerSocket` (reuse `TcpSocketServerTest`/`TcpConnectorTest` patterns); `reconcile` add/remove/update; UP↔DOWN transitions on missed heartbeats.
- **E2E**: `dummy-edge` persistent-session profile → device shows **UP** in the connection table → dispatch a command, delivered over the live socket → kill the device, link flips **DOWN** within `missThreshold` → restart, reconnects and the queued delivery flushes → run two devices, see two live sessions in the table.
