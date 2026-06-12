# Cloud ↔ Edge Proxy — Design & Build Plan

> A **domain-agnostic, durable connector** that bridges any **Cloud** application and any **Edge** target (an on-prem device, machine, or network), in both directions, with **Temporal Cloud** as the durable backbone.
>
> The device-fleet case (a cloud platform ↔ an on-prem edge fleet) is used throughout as the **reference example / demo profile** — it is _not_ baked into the core. Swap the profile and the same proxy connects anything cloud-side to anything on-prem.

---

## How to use this document

This plan is split into **three parts**:

- **Part 1 — Proxy Application (Java + Spring Boot + Maven).** The agnostic connector itself, plus a minimal `dummy-cloud` / `dummy-edge` harness to demo it end-to-end. ✅ **Complete.**
- **Part 2 — Management UI (Next.js).** Standalone web app for lifecycle control (start/stop/restart), guided route configuration, and live Temporal visibility. ✅ **Complete** (`management-ui/`, the "Switchyard" console).
- **Part 3 — Dynamic Message Catalog + Codecs.** Operator-editable message catalog (types, directions, codecs, cloud endpoints) through the UI — the device-fleet profile becomes a seed, not a constraint — plus `xml`/`raw` codecs. **In progress.** (Further hardening & rollout tracked at the end of Part 3.)

> ### ⚠️ Implementation conventions (read before coding)
>
> - **Use the `temporal-developer` skill** for every Temporal-touching task — worker/client setup, workflow & activity authoring, standalone-activity APIs, signals/queries, CLI usage, and debugging non-determinism. The Temporal Java APIs below are described conceptually; **confirm exact current signatures via the skill**, especially **Standalone Activities (Public Preview)**, whose API is still stabilizing.
> - For anything else that you need clarification on, please use the **Temporal Docs connector** to ask specific questions.
> - **Language/stack:** Temporal **Java SDK**. **Spring Boot 3.x** application built with **Maven** (`pom.xml`).
> - **No Docker.** Local development uses the **Temporal CLI dev server** (`temporal server start-dev`) and runs apps via `mvn spring-boot:run` / `java -jar`. A **`justfile`** drives build and demo steps.
> - **Stay agnostic.** Core code refers to **Cloud** and **Edge**, never any one vertical's jargon. Anything domain-specific (message-type names, endpoints, payload formats) lives in a **profile** (configuration), not in core classes.

---

## 1. Context & terminology

The proxy moves **typed messages** between a cloud application and an on-prem target, in both directions:

- **Cloud → Edge** (outbound): the cloud app has something for the on-prem target (a command, task, document, payload).
- **Edge → Cloud** (inbound): the on-prem target has something for the cloud (a confirmation, event, reading, payload).

| Term             | Meaning                                                                                                                                                  |
| ---------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Cloud**        | Any cloud application using the proxy (the _reference example_ is a cloud control plane).                                                        |
| **Edge**         | Any on-prem device, machine, or network endpoint (reference example: a fleet of edge devices).                                                       |
| **Message type** | A named, directional flow defined by configuration (e.g. in the device-fleet profile: `DEVICE_COMMAND`, `COMMAND_RESULT`). The core treats it as an opaque key. |
| **Profile**      | A bundle of config (message catalog + codecs + endpoints + device templates) for a given domain. The **device-fleet profile** ships as the reference/demo.  |

**Device-fleet reference profile (example only):**

| Message type                | Direction    |
| --------------------------- | ------------ |
| `CONFIG_UPDATE`         | Cloud → Edge |
| `CONFIG_ACK`           | Edge → Cloud |
| `DEVICE_COMMAND` | Cloud → Edge |
| `COMMAND_RESULT`              | Edge → Cloud |
| `REPORT_REQUEST`           | Cloud → Edge |
| `REPORT_UPLOAD`       | Edge → Cloud |

The goal is a **universal, multi-tenant** connector that an operator installs on-prem and that "just works," is **remotely controllable** by the cloud-app operator, and is **reconfigurable without redeploys**.

---

## 2. Architecture (applies to all parts)

### Roles

- **Proxy (on-prem)** — the _only_ Temporal **worker**. Connects to Temporal Cloud **egress-only** over the SDK's persistent gRPC channel. **No inbound firewall ports.**
- **Cloud app** — a Temporal **client** (dispatches outbound work and drives the control plane) plus its existing **REST API** (receives inbound messages). Runs **no Temporal worker**.
- **Edge target (on-prem)** — black box. The proxy reaches it on the LAN via pluggable connectors (outbound) and hosts ingress listeners for its pushes (inbound).

### Why this shape

Everything the customer-side box does is **outbound**: proxy → Temporal Cloud (gRPC) and proxy → Cloud REST. The customer never opens an inbound port. Credentials on the customer box are scoped to **their** namespace only, so extraction by the customer exposes only their own data.

### Flows

**Cloud → Edge (outbound).** The cloud client starts a `DeliverToEdge` **workflow** (Workflow ID = `{messageType}-{businessId}`, dedup policy `REJECT_DUPLICATE` + `USE_EXISTING`) on the install's task queue. The proxy worker polls over egress gRPC and runs the workflow, which executes a `TransmitToDevice` activity: look up the route for `(type, CLOUD_TO_EDGE)` → select a `Connector` (HTTP/TCP/FTP) + `MessageCodec` → encode → send to the edge target's channel for that type. Using a workflow gives full visibility in the Temporal UI — each outbound message appears with its activity in the event history.

> **Durability win:** the task lands in Temporal Cloud first, so if the proxy is offline the work waits and delivers on reconnect.

**Edge → Cloud (inbound).** The edge target pushes to a proxy ingress **channel**. The proxy identifies the message type **from the channel, never from the payload** (see §3), then starts a standalone `DeliverToCloud` activity (Activity ID = `{messageType}-{businessId}`, same dedup). The proxy worker runs it, POSTing to that type's cloud endpoint with retries.

> **Reliability levers (cheap, kept):**
>
> - **Ack-after-enqueue** — ack the edge target only _after_ Temporal accepts the activity start, so a retrying device behaves correctly.
> - **Per-transport reliability is documented** — FTP is inherently store-and-forward; raw TCP/HTTP push leans on device retry.
>
> **Out of scope:** no local durable spool. The SDK's auto-reconnecting gRPC channel + Temporal Cloud's 99.9% SLA cover transient cloud-unreachability; the only residual risk (proxy host down) is an HA/shared-responsibility concern a spool can't fix.

### Key design principles

- **Cloud → Edge uses a `DeliverToEdge` workflow** so each outbound dispatch is a first-class Workflow visible in the Temporal UI, with a `TransmitToDevice` activity inside for the actual connector send. **Edge → Cloud uses standalone activities** (`DeliverToCloud`) since that path is a simple fire-and-forget POST — no orchestration needed. The **control plane** (§4) is a long-lived singleton workflow.
- **Workflow/Activity ID = `{messageType}-{businessId}`** is the dedup handle both directions — collapses duplicate cloud starts (workflow ID reuse) and duplicate edge pushes (activity ID reuse) to one execution.
- **Namespace per proxy install** (not task-queue-per-tenant). Per-namespace creds contain the blast radius to that customer's own namespace. The cloud app (trusted/central) holds creds for all namespaces. **Provisioning the customer→namespace+creds mapping lives on the cloud side — out of scope for this repo.**

---

## 3. Channel-based routing (the core of configurability)

**Principle: discriminate by channel, never by payload.** Edge payloads are often standard/opaque and don't self-identify, so the _channel a message uses_ carries the type — symmetrically both directions. Each transport expresses "channel" differently:

| Transport | Inbound channel (→ type)                    | Outbound target (per type)                            |
| --------- | ------------------------------------------- | ----------------------------------------------------- |
| **TCP**   | listen **port**                             | `host:port`                                           |
| **HTTP**  | **path** (e.g. `/command-result`)             | **URL** (e.g. `{baseUrl}/commands`, `/report-requests`) |
| **FTP**   | watch **folder** (+ optional filename glob) | remote **folder/path**                                |

- **One inbound channel carries exactly one message type** → the proxy maps inbound channel → type → codec → cloud endpoint with zero payload inspection. Requires the edge target to be pointed at a distinct path/folder/port per type (true for most devices).
- **Symmetric outbound:** the type is already known (it's a known outbound activity); the proxy just needs that type's target channel.
- **Fallback for opaque-multiplexed devices:** an opt-in `MessageTypeResolver` (filename regex / fixed header offset / small content rule) bound to a single channel that carries multiple types. **Off by default** — ship the SPI + a filename-pattern impl, don't force it on anyone.

### Three-layer config (split by who owns it)

This is what keeps configuration **non-technical** — day-to-day ops only touch layer 3.

1. **Cloud message catalog** — shipped/managed by the cloud-app operator (this is the _profile_); the customer never edits. Defines each message type's _direction_, _default codec_, and _cloud endpoint_. So `COMMAND_RESULT` already knows it's inbound and posts to `/api/command-result`. **Customers never type a URL.**
2. **Site infrastructure** — set once at install by IT. Edge addresses/`baseUrl`, certs, and the **available TCP port pool** (e.g. inbound 6000–6010).
3. **Routing bindings** — the only thing ops edits (via the Part 2 UI). For each message type the site uses: pick edge target + transport, and assign the channel (port/path/folder) via a **guided picker** or pre-filled by a **device template**. Validated before apply (in-pool, no channel collisions).

**Device templates** are first-class: a per-edge-model profile that pre-fills the typical message types and channel layout (standard paths, relative port offsets, folder names). The configurator clones it and sets only `baseUrl`/host + base port — in the common case, nothing is typed by hand.

---

## 4. Control plane — remote on/off + hot config (the one real workflow)

Because the proxy is egress-only, the cloud UI can't reach it directly — so control flows _through Temporal Cloud_, same as data. This is the legitimate "long-lived state + orchestration" case for a real workflow.

**A singleton `ProxyControlWorkflow` per install** (Workflow ID `proxy-control`, in the install's namespace) holds desired state:

```
ProxyControlState { enabled: boolean, devices: EdgeConfig[], version: long }
```

- **Cloud UI drives it via signals** (egress client call): `enable()`, `disable()`, `applyConfig(devices)` / `upsertDevice` / `removeDevice`. Signal handlers **validate** the proposed config and reject bad changes with a clear message before they go live.
- **Proxy reads it via query**: a `ProxyControlPoller` bean queries `getState()` on a short interval; a `Reconciler` **hot-applies** changes — starts/stops ingress listeners, builds/tears down connectors, applies routing, flips enabled — **with no restart**.
- **Continue-as-new** periodically to bound history.

> **On/off = soft disable:** stop ingress listeners and pause outbound processing, but keep the lightweight control poller (and the egress connection) alive so the cloud can turn it back **on**. This is the only remotely-_reversible_ form of "off."
>
> **Config source of truth:** Temporal holds _operational_ config (devices, routing, enabled — hot-updatable). Only _bootstrap_ config (namespace, certs, target) stays local.

---

# PART 1 — Proxy Application (Java + Spring Boot + Maven) · ✅ COMPLETE

The full agnostic connector plus a minimal harness to demo it. Runs against the always-on Docker Temporal stack (`localhost:7233`, Server 1.31.1) or the CLI dev server (`just temporal-dev`).

## 1.1 Project & build

- **Maven** single module, `pom.xml`. Java 17+. Spring Boot 3.x.
- **Dependencies:**
  - `io.temporal:temporal-spring-boot-starter` (GA artifact, latest 1.35.x — **not** the deprecated `-alpha`; transitively pulls `temporal-sdk` + autoconfigure)
  - `org.springframework.boot:spring-boot-starter-web` (HTTP ingress + admin endpoints)
  - `org.springframework.boot:spring-boot-starter-validation`
  - TCP: plain Java NIO `ServerSocketChannel` (no extra dep) or **Netty** if framing gets complex
  - FTP: **Apache FtpServer** (`org.apache.ftpserver:ftpserver-core`) for the ingress drop-server + `WatchService`; **Apache Commons Net** (`commons-net`) `FTPClient` for outbound
  - Test: `spring-boot-starter-test`, Temporal `temporal-testing`

> Use the **`temporal-developer` skill** to scaffold the Temporal pieces and to confirm the **standalone-activity** client API in the current Java SDK.

## 1.2 `application.yml` (bootstrap only)

Two profiles. **`local`** targets the dev server (no mTLS); **`cloud`** targets Temporal Cloud with mTLS.

```yaml
# default / cloud
spring:
  temporal:
    connection:
      target: ${TEMPORAL_TARGET} # <ns-id>.<region>.tmprl.cloud:7233
      mtls:
        key-file: ${TEMPORAL_KEY_FILE}
        cert-chain-file: ${TEMPORAL_CERT_FILE}
    namespace: ${TEMPORAL_NAMESPACE} # <tenant>.<account-id>
    workers-auto-discovery:
      packages: [com.proxyapp.temporal, com.proxyapp.control]
proxy:
  task-queue: proxy-main
  profile: device-fleet # which message catalog/profile to load
  ingress:
    http-port: 8080
---
spring:
  config.activate.on-profile: local
  temporal:
    connection:
      target: 127.0.0.1:7233
    namespace: default
```

- Activities/workflows via auto-discovery (`@ActivityImpl` / `@WorkflowImpl`, ordinary Spring beans). Inject `WorkflowClient` with `@Autowired`.
- **Never** set `insecure-trust-manager`/`server-name` against Cloud. Use single-namespace config (avoid `spring.temporal.namespaces[]`).

## 1.3 Package structure (`com.proxyapp`)

```
config/        TemporalConfig; bootstrap ProxyProperties (@ConfigurationProperties)
control/       ProxyControlWorkflow (+Impl)   # singleton: {enabled, devices[]} via signals + query
               ProxyControlPoller             # queries control wf on an interval
               Reconciler                     # diff desired vs running; start/stop listeners + connectors
routing/       MessageType, Direction (CLOUD_TO_EDGE | EDGE_TO_CLOUD)
               EdgeConfig, RouteBinding, Channel (port|path|folder)
               RouteTable                     # resolve (type,dir) -> connector target / ingress channel -> cloud endpoint
               MessageCatalog                 # the loaded profile (types, codecs, cloud endpoints)
               DeviceTemplate                 # per-edge-model profiles
               ConfigValidator                # pool membership + channel-collision checks
               MessageTypeResolver (SPI)       # FilenamePatternResolver impl, opt-in
temporal/
  workflow/    DeliverToEdgeWorkflow (+Impl)  # outbound: cloud starts this, proxy worker runs it
  activity/    DeliverToEdgeActivity (+Impl)  # outbound: TransmitToDevice — route -> connector send
               DeliverToCloudActivity (+Impl) # inbound: standalone activity -> POST to cloud endpoint
connector/     Connector (SPI: send), HttpConnector, TcpConnector, FtpConnector, ConnectorFactory
codec/         MessageCodec (SPI), JsonCodec (default; FixedWidth/Xml later)
ingress/       HttpIngressController, TcpSocketServer, FtpIngressListener (SmartLifecycle beans)
               InboundGateway                 # channel -> type -> decode -> start DeliverToCloud -> ack
profile/       built-in profiles, incl. DeviceFleetProfile (the reference example)
model/         canonical message DTOs
```

**SPIs**

- `Connector { void send(ChannelTarget target, byte[] payload); }`
- `MessageCodec { byte[] encode(CanonicalMessage m); CanonicalMessage decode(byte[] raw); }`
- `MessageTypeResolver { MessageType resolve(InboundContext ctx); }` (opt-in)

**Ingress coexistence.** HTTP via Spring MVC (path = channel). `TcpSocketServer` and `FtpIngressListener` are `SmartLifecycle` beans with their **own** `ExecutorService` accept-loops (never Tomcat/Temporal poller threads); the `Reconciler` opens/closes them as config changes. Listeners stay thin — channel → `InboundGateway` → ack.

## 1.4 Test harness (`dummy-cloud` + `dummy-edge`)

> **Superseded:** the original separate-repos decision was replaced by a **monorepo** — the dummies now live in `dummy-cloud/` and `dummy-edge/` beside `proxy/`, built by the root aggregator pom.

Each is a Spring Boot + Maven app running the **Device-fleet reference profile**. They exist only to exercise the proxy; the `justfile` runs them.

- **`dummy-cloud`** (port `8081`) — Temporal **client**. REST/`/demo/*` endpoints to start `DeliverToEdge` for any message type; control-plane drivers (`enable`/`disable`/`applyConfig` signals) to exercise remote on/off + hot reconfig; REST endpoints per inbound type that receive `DeliverToCloud` and log/store. Copies the catalog + DTOs.
- **`dummy-edge`** (port `8082`) — exposes all three transports with per-type channels (HTTP paths, TCP ports, FTP folders). On an outbound message it auto-generates the paired confirm and pushes it back over the same transport/channel.

## 1.5 Build order (Part 1)

1. ~~**Routing core** — `MessageType`/`Direction`, `EdgeConfig`/`RouteBinding`/`Channel`, `MessageCatalog`, `RouteTable`, `ConfigValidator`, and the `DeviceFleetProfile`. Pure logic, **unit-tested first** (no Temporal needed).~~ ✅
2. ~~**Data path (HTTP)** — Temporal starter + bootstrap config, `DeliverToEdge` workflow + `DeliverToCloud` activity, `Connector` SPI + `HttpConnector`, `JsonCodec`, `HttpIngressController` + `InboundGateway`.~~ ✅
3. ~~**Dummies (HTTP)** — `dummy-cloud` + `dummy-edge` for the command / result pair → **first end-to-end demo** (`just demo-command`).~~ ✅
4. ~~**Control plane** — `ProxyControlWorkflow` + `ProxyControlPoller` + `Reconciler`; drive `enable`/`disable`/`applyConfig` from `dummy-cloud`.~~ ✅
5. ~~**Transports** — `TcpConnector`/`TcpSocketServer` (port pool), then `FtpConnector`/`FtpIngressListener` (folders); extend dummies. Add remaining message types via the catalog.~~ ✅
6. ~~**Polish** — device templates + opt-in `FilenamePatternResolver`; per-transport reliability docs.~~ ✅

## 1.6 Verification (Part 1) ✅

Verified against the always-on Docker Temporal (Server 1.31.1, `localhost:7233`). Temporal UI at `http://localhost:8080`.

- ~~**E2E (HTTP):** `just demo-command` → trigger `DEVICE_COMMAND` from `dummy-cloud` → `dummy-edge` receives on `/commands` → it pushes `COMMAND_RESULT` to the proxy's `/command-result` channel → confirm lands at `dummy-cloud`'s command-result endpoint. Inspect the `DeliverToEdge` workflow + `TransmitToDevice` activity in the Temporal UI.~~ ✅
- ~~**Per type & transport:** repeat for put-away and report-requests pairs over TCP (ports) and FTP (folders); verify channel-based type resolution with no payload inspection.~~ ✅
- ~~**Control plane:** `disable()` → ingress stops & outbound pauses, proxy stays connected → `enable()` resumes. `applyConfig` adding/moving a channel → hot reload, no restart. Push an invalid config (port collision / out of pool) → validation rejects with a clear message.~~ ✅
- ~~**Idempotency:** duplicate outbound start and duplicate edge push → exactly one execution each.~~ ✅
- **Proxy-down outbound durability:** stop proxy, dispatch a message, restart → delivers on reconnect. _(not yet verified)_
- **Egress-only:** confirm the proxy reaches Temporal with only outbound connectivity. _(not yet verified)_

---

# PART 2 — Management UI (Next.js) · ✅ COMPLETE

A standalone web app for operations and solutions consultants to manage a proxy install: lifecycle control, route configuration, and live Temporal visibility — without touching code or a terminal.

> ### Design goal
>
> The primary user is a **solutions consultant** (device-fleet domain, not deeply technical). The UI must be simple enough that the dev team is not involved in day-to-day config changes. Technical depth (Temporal event history, raw JSON) is available but tucked behind progressive disclosure — the happy path stays clean.

## 2.1 Project & stack

- **Next.js 14+** (App Router), TypeScript.
- **ShadCN** component primitives + **custom Tailwind** for a clean, original UI. No off-the-shelf dashboard templates, no generic AI-generated layouts. The design should feel intentional and product-grade — custom color palette, purposeful spacing, typographic hierarchy. Build a component library from ShadCN primitives that reflects the product's identity.
- Lives in the monorepo as `management-ui/`. Own `package.json`; no Maven involvement.
- **Justfile recipes:** `just run-ui` (dev server), `just build-ui` (production build).
- **Port:** `3000` (default Next.js dev port — no conflict with the 7233/8080/8090–8092 range).

## 2.2 Architecture

> ### Key constraint: the UI never talks to the proxy directly.
>
> The proxy is **on-prem behind a firewall**. The only network path out is the Temporal egress gRPC channel. The management UI runs on the **cloud side**. Every interaction between the UI and the proxy flows **through Temporal** — signals down, queries up. This means IT never opens an inbound port for management traffic; the only thing they maintain is the Temporal Cloud connection.

```
         CLOUD SIDE                           │  ON-PREM (firewall)
                                              │
  ┌──────────────────────────┐                │  ┌─────────────┐
  │  Management UI (:3000)   │                │  │  Proxy app  │
  │                          │                │  │  (:8090)     │
  │  React ←→ API routes     │                │  │             │
  │        │                 │                │  │  Control    │
  │   @temporalio/client     │                │  │  poller ←──────── queries/signals
  │        │                 │                │  │             │
  └────────┼─────────────────┘                │  └──────┬──────┘
           │                                  │         │
           ▼                                  │         │ egress gRPC only
  ┌──────────────────┐                        │         │
  │  Temporal Server  │◄──────────────────────│─────────┘
  │  (:7233)          │                       │
  └──────────────────┘                        │
           ▲                                  │
  ┌────────┴─────────┐                        │  ┌─────────────┐
  │  dummy-cloud     │  (demo dispatch        │  │  dummy-edge  │
  │  (:8091)         │   via REST — both      │  │  (:8092)     │
  └──────────────────┘   on cloud side)       │  └─────────────┘
```

**Backend (Next.js API routes) — every call flows through Temporal:**

| Concern | How |
|---|---|
| **Control plane** (enable, disable, applyConfig, upsertDevice, removeDevice) | `@temporalio/client` — signal the `proxy-control` workflow |
| **Control state** (enabled, devices, version, lastError) | `@temporalio/client` — query `getState` on the control workflow |
| **Proxy applied status** (listeners, applied version, health) | `@temporalio/client` — query the control workflow (proxy reports applied state back via signals; see §2.3) |
| **Proxy lifecycle** (stop, restart) | `@temporalio/client` — signal `shutdown` / `restart` on the control workflow (proxy acts on it locally; see §2.3) |
| **Temporal visibility** (workflow list, activity list, event history) | `@temporalio/client` — `WorkflowService` visibility APIs |
| **Demo dispatch** (fire test messages) | REST → dummy-cloud `/demo/*` endpoints (both on the cloud side — no firewall crossing) |

**The browser never talks to Temporal or any backend directly** — all external calls go through Next.js server-side API routes, keeping credentials and network topology off the client.

## 2.3 Proxy lifecycle management

Everything goes through Temporal. No direct REST calls to the proxy.

### Proxy-side changes (control workflow extensions)

The `ProxyControlWorkflow` gains new signals and enriched query state:

**New signals:**
- `shutdown` — the proxy's control poller detects this, initiates graceful JVM shutdown (equivalent to Actuator shutdown, but triggered via Temporal). The supervisor wrapper restarts the process.
- `restart` — sugar for shutdown-then-start: the poller shuts down the JVM, the supervisor relaunches it. The restarted proxy reconnects to Temporal and resumes polling.

**Enriched query state:** The proxy's `ProxyControlPoller` / `Reconciler` signals back its **applied status** after each reconciliation:
- Applied config version (confirms the proxy has caught up to desired state)
- Active listeners (HTTP paths, TCP ports, FTP folders)
- Process uptime / last-seen timestamp
- Health (healthy / degraded / unreachable — the UI infers "unreachable" when last-seen exceeds a threshold)

This lets the UI show live proxy state without ever reaching the proxy's network. The query returns both **desired** state (what the cloud pushed) and **applied** state (what the proxy reports).

### Two tiers of control in the UI

| Action | Mechanism | What happens |
|---|---|---|
| **Soft disable** | Signal `disable` on the control workflow | Data flow stops (ingress listeners shut down, outbound pauses). Process stays alive, control poller stays connected. Instantly reversible. |
| **Soft enable** | Signal `enable` on the control workflow | Data flow resumes. |
| **Hard restart** | Signal `restart` on the control workflow | Proxy poller detects the signal, initiates graceful JVM shutdown. Supervisor wrapper relaunches. UI shows a "restarting..." state and polls the query until the proxy reports back. |
| **Hard stop** | Signal `shutdown` on the control workflow | Same as restart, but the supervisor is configured to stay down (or the UI signals disable first, then shutdown). |

**Proxy-side infra:**
- Add `spring-boot-starter-actuator` to the proxy (the poller calls `SpringApplication.exit()` internally when it receives a shutdown/restart signal — Actuator is used for graceful shutdown hooks, not as an exposed endpoint).
- `just run-proxy-managed` recipe: a wrapper script that runs the proxy in a restart-on-exit loop. Shutdown exits the JVM → wrapper relaunches. For production, this role is filled by systemd / Windows Service.

## 2.4 Route configuration (guided wizard)

The target user is a solutions consultant who knows the device-fleet domain but isn't deeply technical. The flow prioritizes **guided simplicity over raw power**. Advanced users can drop to a JSON editor.

### Wizard flow (3 steps)

**Step 1 — Choose a device template.** Select from the profile's built-in templates (e.g., "Standard edge gateway" for the device-fleet profile). The template pre-fills message types, transports, and channel layout — the most common case needs zero manual binding.

**Step 2 — Set site values.** Fill in the site-specific fields the template can't know:
- Device ID (a friendly name like `conveyor-east`)
- Base URL / host (e.g., `http://192.168.1.50:8082`)
- FTP credentials (if FTP bindings exist)
- Base TCP port (the template applies offsets from this; the UI shows the resulting ports and highlights any pool conflicts)

**Step 3 — Review & apply.** A summary card shows every binding:

| Message type | Direction | Transport | Channel | Target |
|---|---|---|---|---|
| DEVICE_COMMAND | Cloud → Edge | HTTP | /commands | http://192.168.1.50:8082/commands |
| COMMAND_RESULT | Edge → Cloud | HTTP | /command-result | (proxy listens) |
| ... | | | | |

**Validation runs client-side AND server-side** (mirrors `ConfigValidator`): port-pool membership, no channel collisions, transport/kind agreement, direction checks. Errors are inline, human-readable (e.g., "TCP port 7777 is outside the available pool 6000–6010"), not stack traces.

**Apply** sends an `upsertDevice` signal to the control workflow. The UI polls the control state until `version` increments (confirming the proxy reconciled) and shows a success toast. If `lastError` is set, it displays the rejection reason.

### Advanced mode

A toggle exposes:
- Raw JSON editor for the full `EdgeConfig` (for power users / edge cases the wizard doesn't cover)
- `applyConfig` (replace all devices at once)
- `removeDevice` by ID

## 2.5 Temporal dashboard (live visibility)

The goal: a non-technical user can see "what's flowing" without opening the Temporal CLI or the raw Temporal UI.

### Activity feed

A live-updating table of recent `DeliverToEdge` workflows and `DeliverToCloud` standalone activities:

| Timestamp | Type | Message | Direction | Status | Duration |
|---|---|---|---|---|---|
| 3:48 PM | DEVICE_COMMAND-ORD-3001 | DeliverToEdge | Cloud → Edge | Completed | 161ms |
| 3:48 PM | COMMAND_RESULT-ORD-3001 | DeliverToCloud | Edge → Cloud | Completed | 45ms |

- **Color-coded status**: green (completed), yellow (running), red (failed/timed out).
- **Click to expand**: shows the workflow/activity event history — scheduled, started, completed, with timestamps. For `DeliverToEdge` workflows, shows the `TransmitToDevice` activity inside.
- **Auto-refresh** on a short interval (2–5s), with a pause button.

### Proxy status panel

Queries the control workflow (both desired and applied state). Shows at a glance:
- **Connection indicator**: green (proxy reporting in), amber (last seen > threshold), red (unreachable)
- **Enabled/disabled** state
- **Active listeners**: HTTP paths, TCP ports, FTP folders the proxy has applied
- **Config version**: desired vs applied (shows if the proxy is behind)
- **Last error** (if a config push was rejected)

### Control workflow inspector

Progressive disclosure — collapsed by default. Expands to show the `proxy-control` workflow's current state, event count, and continue-as-new history.

## 2.6 Screens (summary)

1. **Dashboard** — proxy status panel (connection, enabled, listeners, config version) + activity feed (recent workflows/activities) + quick-action buttons (enable/disable/restart).
2. **Route Config** — device list with current bindings; "Add Device" launches the wizard; edit/remove existing devices. Advanced toggle for raw JSON.
3. **Temporal** — full activity feed with filtering (by type, direction, status, time range) + expandable event history. Control workflow inspector.
4. **Demo** — test dispatch buttons (fire a DEVICE_COMMAND, CONFIG_UPDATE, or REPORT_REQUEST with sample payloads). Shows the resulting workflow/activity in the feed in real time. _(Wired to dummy-cloud's `/demo/*` endpoints — both on the cloud side.)_

## 2.7 Build order (Part 2)

1. ~~**Scaffold** — Next.js app with ShadCN + custom Tailwind theme. Justfile recipes. `@temporalio/client` connecting to `localhost:7233`. Establish the visual language (palette, typography, spacing, component patterns).~~ ✅
2. ~~**Control plane** — enable/disable/restart via signals. Query control workflow state. Dashboard with status panel + quick-action buttons.~~ ✅
3. ~~**Proxy status reporting** — extend `ProxyControlWorkflow` with applied-state signals. Update the poller to report back. UI shows desired vs applied state and connection health.~~ ✅
4. ~~**Temporal dashboard** — activity feed listing recent workflows/activities via visibility APIs. Click-to-expand event history.~~ ✅
5. ~~**Route config wizard** — template picker → site values → review → apply. Validation mirroring `ConfigValidator`.~~ ✅
6. ~~**Demo panel** — test dispatch buttons wired to dummy-cloud endpoints. Live feed showing the result.~~ ✅
7. ~~**Polish** — loading states, error handling, toasts, responsive layout, keyboard accessibility.~~ ✅

## 2.8 Verification (Part 2) ✅

- ~~**Zero direct proxy access:** confirm no network calls from the UI or its API routes to the proxy's IP/port. All state comes through Temporal queries; all commands go through Temporal signals.~~ ✅
- ~~**Lifecycle:** soft disable/enable via UI → proxy status reflects in the control workflow query. Hard restart via signal → proxy goes down, comes back, applied-state query resumes.~~ ✅ (restart verified: new PID, supervisor relaunch, fresh applied report)
- ~~**Route config:** apply a valid device → proxy reconciles, applied state shows new listeners. Push an invalid config → inline error, desired state unchanged.~~ ✅ (out-of-pool port rejected with the validator's message)
- ~~**Visibility:** fire a test dispatch from the demo panel → workflow appears in the activity feed within seconds, click to see the `TransmitToDevice` activity in the event history.~~ ✅
- **Persona test:** a non-developer can add a new edge device using only the wizard (no JSON, no terminal) in under 2 minutes. _(needs a human tester)_
- ~~**Design review:** the UI does not look like a generic dashboard template. Custom visual identity, purposeful layout, polished interactions.~~ ✅ (industrial control-room identity: paper/ink/safety-orange, schematic panels, LED lamps)

---

# PART 3 — Dynamic Message Catalog + Codecs

> ### Why this part exists
>
> The proxy was built from a real integration headache (XML-over-TCP with custom framing).
> The point of open-sourcing it is **general use** — anyone with a similar cloud↔edge problem
> should be able to model *their* message types without writing Java. Until Part 3 the message
> catalog was hardcoded in `DeviceFleetProfile` and the validator rejected any type not in it, so
> "configure anything" stopped at the six device-fleet types. Part 3 makes the catalog **operational
> state** the operator edits through the Switchyard UI — the device-fleet profile becomes a *seed*,
> not a constraint — and ships **xml** and **raw** codecs alongside **json** so payload formats
> aren't limited to JSON either.

## 3.1 Catalog as control-plane state

The message catalog moves from a boot-time Java object into the `ProxyControlWorkflow` state,
right beside the device config it already holds. It is edited via signals, queried like
everything else, and hot-applied by the proxy with no restart — the same control loop §4
describes for routing.

`ProxyControlState` gains `List<CatalogEntryDto> catalogEntries` (a flat, Jackson-friendly
record: `type`, `direction`, `codec`, `cloudEndpoint`, `businessIdField`). The existing
`typeDirections` map stays as a **derived projection** (recomputed whenever the catalog
changes) so the device-binding validation in the signal handlers is unchanged.

**New signals on `ProxyControlWorkflow`:**

| Signal | Behavior (validated deterministically in-handler, rejected with `lastError` on failure) |
|---|---|
| `upsertMessageType(CatalogEntryDto)` | Add/replace one type. Checks: non-blank name, valid `direction`, known `codec`, `cloudEndpoint` required for `EDGE_TO_CLOUD`. |
| `removeMessageType(String)` | Remove one type — **rejected if any device binding still references it** (lists the offending devices). |
| `importCatalog(List<CatalogEntryDto>)` | Replace the whole catalog (profile import / reset). Rejected if it would orphan an existing binding. |

Validation lives in a new pure `CatalogValidator` (mirrored byte-for-byte in the UI's
`validate.ts`, same as `ConfigValidator`).

## 3.2 Profile seeds, then the catalog is mutable

`ProxyControlStarter.ensureStarted()` seeds `catalogEntries` from the active profile when it
creates a **new** control workflow. Because the starter uses `USE_EXISTING`, a workflow that
predates Part 3 keeps `catalogEntries == null`; the proxy detects that and **falls back to the
boot profile catalog** (full backward compatibility — nothing breaks on upgrade). The UI's
Catalog page offers "Import device-fleet profile" to populate the state and start editing.

## 3.3 Proxy-side application

- **`Reconciler.apply()`** rebuilds a `MessageCatalog` from `state.catalogEntries` each reconcile
  (fallback to the injected profile catalog when null/empty), then validates devices and builds
  the `RouteTable` against it — so catalog edits go live in seconds, no restart.
- **`DeliverToCloudActivityImpl`** reads the catalog from `routingState.table().catalog()`
  (rebuilt each reconcile) instead of the boot bean, so inbound deliveries use the live
  `cloudEndpoint`.

## 3.4 Codecs: json, xml, raw

`CodecRegistry` registers all three at startup; each type's `codec` field picks one.

| Codec | Decode (edge → canonical) | Encode (canonical → edge) | Business id |
|---|---|---|---|
| **json** | parse JSON, read `businessIdField` | payload as-is | field value, else content hash |
| **xml** | parse with the JDK `DocumentBuilder` (XXE-hardened), read the `businessIdField` element's text | payload as-is | element text, else content hash |
| **raw** | passthrough, no parsing | payload as-is | content hash only (binary/opaque formats) |

`encode` is passthrough for all three — the proxy routes payloads, it doesn't transform them;
the only decode work is **business-id extraction for dedup**.

## 3.5 Management UI — Message Types page

A new **Catalog** tab (between Routes and Temporal) lists every type with its direction, codec,
cloud endpoint, and business-id field. Add/Edit open a guided form (`type-form.tsx`); Remove
confirms and surfaces the workflow's in-use rejection. An **Import device-fleet profile** action
seeds a fresh/legacy install. The device wizard gains a **build-from-scratch** path so an
operator can bind *any* catalog type (not only the starter template), completing the
"configure anything" loop. Validation mirrors `CatalogValidator`; vitest parity vectors keep
the two in lockstep (like the WireString tests).

## 3.6 Build order (Part 3)

1. `CatalogEntryDto` + `ProxyControlState.catalogEntries` + `CatalogValidator`
2. Three catalog signals in `ProxyControlWorkflow(+Impl)`; `ProxyControlStarter` seeding
3. `Reconciler` dynamic-catalog rebuild; `DeliverToCloudActivityImpl` reads live catalog
4. `XmlCodec` + `RawCodec` + shared `ContentHash`; register in `ProxyAppConfig`
5. Java tests: catalog signals (accept/reject/in-use/orphan), `XmlCodec`/`RawCodec`, `CatalogValidator`
6. UI: types + signal mappings + `validate.ts` rules + device-fleet starter bundle
7. UI: Catalog page + type form + Catalog tab; wizard build-from-scratch; vitest parity
8. E2E verify (backward-compat, demo regression, custom type, xml, remove-protection) + commit

## 3.7 Verification (Part 3)

- **Backward compat:** an existing `proxy-control` workflow (no `catalogEntries`) keeps routing
  via the profile catalog; "Import device-fleet profile" from the UI populates the state.
- **Demo regression:** `just demo-command` unchanged.
- **Custom type E2E:** define `CUSTOM_EVENT` (EDGE_TO_CLOUD, json, `/api/custom-event`, `eventId`)
  in the UI, bind a device to it, push a payload, see it arrive at the cloud endpoint.
- **XML codec E2E:** a type with `codec=xml` decodes and extracts its business id from an element.
- **Remove protection:** removing an in-use type is rejected with the device name; unknown codec
  is rejected.
- **Persona test:** a non-developer defines a new type and routes it end-to-end with the UI only.

## Later — Hardening & Rollout (not started)

- **More transports / framing:** SFTP; HTTP auth schemes; additional TCP framings beyond the
  configurable start/stop delimiters already shipped.
- **More codecs:** fixed-width and delimited (CSV) behind `MessageCodec`, per-(edge,type) selection.
- **Profile & template library:** ready-made profiles beyond the device-fleet starter; catalog/template import/export.
- **Observability:** structured logging, metrics (per-type throughput, retries, reconcile events),
  health endpoints; surface activity failures back to the cloud.
- **On-prem packaging (no Docker):** `java -jar` + a service unit (systemd/Windows service) and a
  config bootstrap (namespace, certs, target). Document the install/runbook.
- **Multi-tenant onboarding:** scripted namespace + cert provisioning on the cloud side
  (out of this repo, but documented).

---

## Appendix — TCP wire protocol (configurable framing + ACK/NAK)

Real devices frame TCP messages with start/stop characters (e.g. MLLP's `0x0B…0x1C 0x0D`)
and use protocol-specific ack strings. A `tcpProtocol` block — on a device (default for
all its TCP bindings) or on a single binding (override) — configures this; absent means
legacy behavior (EOF framing, `ACK {id}\n`/`ERR …` replies, `ACK` expected outbound).

| Field | Meaning |
| ----- | ------- |
| `startDelimiter` | frame start (optional; requires `endDelimiter`) |
| `endDelimiter`   | frame end; unset = EOF-framed. When set, inbound connections are persistent: multiple frames per connection, each acked individually |
| `ackReply` / `nakReply` | inbound reply templates, sent **verbatim** after decoding; `{activityId}` / `{reason}` substituted — embed framing chars yourself if your protocol frames acks |
| `expectedAck`    | outbound: bytes that must appear **anywhere** in the device reply (so `ACK` matches a framed ack; beware a device that naks with `NACK` — use a distinguishing string) |
| `awaitReply`     | `false` = fire-and-forget (delivery weakens to "TCP write accepted") |

Strings use **WireString** escapes: printable ASCII, `\\` `\r` `\n` `\t` `\<` `\xHH`, and
named control tokens `<NUL>`–`<US>` + `<DEL>` (full C0 set: `<STX>` `<ETX>` `<VT>` `<FS>`
`<CR>` `<LF>` `<ACK>` `<NAK>` …). Identical parsers in Java
(`routing/WireString.java`) and the UI (`lib/wire-string.ts`).

Demo: `just run-dummy-edge-framed` (device speaks MLLP) + `just demo-config-tcp-framed`
(hot-applies `config/framed-routes.json`, runs the config round trip framed both ways).

## Appendix — Local ports

> Updated for the always-on Docker Temporal stack (`~/git/temporal/docker-compose.yml`):
> its Web UI owns 8080, so the demo apps moved to 8090–8092.

| Component                          | Port      |
| ---------------------------------- | --------- |
| Temporal server (Docker, gRPC)     | 7233      |
| Temporal Web UI (Docker)           | 8080      |
| Temporal Web UI (`just temporal-dev` fallback) | 8233 |
| Proxy HTTP ingress / admin         | 8090      |
| dummy-cloud                        | 8091      |
| dummy-edge                         | 8092      |
| Management UI (Next.js)            | 3000      |
| Proxy TCP inbound pool (example)   | 6000–6010 |

See `justfile` for build and demo recipes.
