# Internals & reliability

Design internals of the proxy: how hot reload works, how to evolve the control workflow safely, the proxy module's package layout, and the per-transport reliability profile. For orientation see the [README](../README.md); for the roads not taken see [design-alternatives.md](design-alternatives.md).

---

## Hot reload & evolving `proxy-control`

### How hot reload works

An **Update** validates, mutates the workflow's desired state, and bumps `version` (an invalid change is rejected right there, with `lastError`, and never goes live). The workflow is **push-based**: on the change it schedules a **`reconcile` activity** ([`ControlActivities`](../proxy/src/main/java/com/proxyapp/temporal/activity/ControlActivities.java)) on the proxy worker, which calls [`Reconciler.apply`](../proxy/src/main/java/com/proxyapp/control/Reconciler.java) to **atomically swap** the route table and reconcile HTTP/TCP/FTP ingress listeners, persistent TCP sessions, and the data worker's polling — **with no restart**. That activity **returns the applied state**, which the workflow records for the cloud to read (desired-vs-applied) at no extra Action. Between changes the workflow parks on `Workflow.await` (zero Actions); on boot the proxy ([`ControlBootstrap`](../proxy/src/main/java/com/proxyapp/control/ControlBootstrap.java)) sends one `requestReconcile` so a freshly-started proxy applies current desired state immediately.

**No code change needed** to add/edit **message types** or **devices** — they're data carried by signals. **Code + redeploy** is only needed to add a new **codec** or **transport** (those are compiled SPIs: `MessageCodec` / `Connector`).

### Safely changing the workflow itself

`ProxyControlWorkflow` is a long-running singleton (it `continueAsNew`s when the server suggests it via `isContinueAsNewSuggested()`), so edits must stay **replay-safe** for the in-flight `proxy-control` execution. There is intentionally **no `Workflow.getVersion` patching today** — the design stays additive instead. State is serialized by Jackson **by field name** (no `@JsonTypeInfo`/`@class`), which is what makes additive changes safe.

- ✅ **Replay-safe (just deploy a new worker):** add a new `@UpdateMethod`/`@SignalMethod`/`@QueryMethod`; add a new **optional** `ProxyControlState` field with a default value; add deterministic validation. Old histories simply never exercised the new path.
- ⚠️ **Needs `Workflow.getVersion` (or draining/replacing the `proxy-control` run):** changing the _logic_ of an existing Update/signal handler, renaming/retyping a state field, or introducing any non-deterministic/IO behavior into the workflow. See Temporal's versioning guidance and the additive-only rationale in [design-alternatives.md](design-alternatives.md) (§12).

Because the catalog and devices are _data_, most real-world change is config (signals), not workflow code — which is the point of the design.

### Lifecycle / remote restart

`requestRestart` / `requestShutdown` set a durable lifecycle command on the workflow, which pushes it to the proxy as a one-shot `deliverLifecycle` activity. The proxy's [`LifecycleController`](../proxy/src/main/java/com/proxyapp/control/LifecycleController.java) acks it (so a relaunched proxy won't replay it), then exits the JVM on a short delay — code `10` for restart, `0` for shutdown. [`scripts/proxy-supervisor.sh`](../scripts/proxy-supervisor.sh) relaunches on exit `10` and stays down otherwise. Run the proxy under the supervisor (`just run-proxy-managed`) to enable the UI's RESTART button. All of this rides the proxy's existing egress gRPC — nothing dials in.

#### The supervisor contract (how "remote restart" actually restarts)

The proxy can't relaunch itself once the JVM exits, so an external **supervisor** does it. Crucially the code **never calls the supervisor** — it's the inverse: the supervisor *wraps* the JVM (launches `java -jar` in a loop) and the two communicate only across the **process boundary**, over two channels:

- **Exit code (JVM → supervisor).** `LifecycleController` maps the command to a code — `10` (`RESTART_EXIT_CODE`) for restart, `0` for shutdown — and calls `System.exit(code)`. The supervisor's loop reads `$?`: on `10` it re-loops and relaunches; on anything else it exits and stays down. "Restart me" is expressed purely by *how the process exits* — there is no RPC or callback.
- **`PROXY_SUPERVISED` env var (supervisor → JVM).** The supervisor exports `PROXY_SUPERVISED=true`; the proxy reads it (`LifecycleController`, `AppliedStatusReporter`) to know a supervisor exists. If it's unset, the proxy logs *"no supervisor detected — nothing will relaunch me"* and reports un-supervised status, so the **Switchyard UI warns before RESTART** rather than silently killing a proxy that won't come back.

Because the contract is just "relaunch on exit 10, set `PROXY_SUPERVISED`," the dev-only [`proxy-supervisor.sh`](../scripts/proxy-supervisor.sh) is a stand-in for **systemd** (`Restart=on-failure`) or a Windows service in production — the proxy code knows nothing about which one is wrapping it. The script also runs from a **copied jar** (`proxy-app-run.jar`), so you can rebuild the original jar under a running proxy and have the next RESTART pick up the new build — i.e. "rebuild, then hit RESTART in the UI" behaves like a real redeploy.

---

## Architecture map (proxy module)

```
proxy/src/main/java/com/proxyapp/
├── ProxyAppApplication            Spring Boot entry point (worker auto-discovered under temporal/)
├── config/        ProxyProperties (bootstrap), ProxyAppConfig (bean wiring), ActivityClient bean
├── controller/    AdminController (/admin/status), HttpIngressController (HTTP edge→cloud ingress)
├── temporal/
│   ├── workflow/  DeliverToEdgeWorkflow(+Impl) · ProxyControlWorkflow(+Impl)
│   └── activity/  DeliverToEdgeActivity ("TransmitToDevice") · DeliverToCloudActivity
│                  · ControlActivities ("Reconcile" / "DeliverLifecycle") (+Impls)
├── control/       ProxyControlStarter · ControlBootstrap · Reconciler · AppliedStatusReporter
│                  · LifecycleController · CatalogValidator
│   └── model/     ProxyControlState · CatalogEntryDto · AppliedStatus
├── ingress/       InboundGateway (channel→type→decode→enqueue→ack) · TcpSocketServer
│                  · FtpIngressListener · InboundSink (SPI) · IngressException
├── routing/       MessageCatalog · RouteTable · RoutingState · ConfigValidator · WireString
│   │              · MessageTypeResolver (SPI) + Filename/ContentPatternResolver
│   └── model/     MessageType · Direction · Transport · Channel · ChannelKind · CatalogEntry
│                  · RouteBinding · EdgeConfig · DeviceTemplate · TcpProtocol · TcpSession · ResolverConfig
├── connector/     Connector (SPI) · ConnectorFactory · Http/Tcp/FtpConnector · ConnectorSendException
│   └── model/     ChannelTarget (+ HttpTarget, TcpTarget, FtpTarget)
├── session/       TcpSessionManager (per-device connection table) · DeviceSession · SessionSendException
│   └── model/     DeviceSessionConfig · DeviceSessionState · DeviceSessionStatus
├── codec/         MessageCodec (SPI) · CodecRegistry · Json/Xml/RawCodec · ContentHash
├── profile/       Profile (SPI) · ProfileRegistry · DeviceFleetProfile · EmptyProfile
└── model/         CanonicalMessage (the message envelope, shared across the data path)
```

A domain gets a `model/` subpackage when it owns data types; SPIs and exceptions stay with their service impls (so `codec/` and `profile/` have no `model/`).

---

## Per-transport reliability profile

| Transport                      | Inbound (edge → proxy)                                                                                                                                                      | Outbound (proxy → edge)                                                                                                                                                     |
| ------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **HTTP**                       | Device gets `202` only after Temporal accepted the enqueue (`503` while disabled, `404` unbound channel). Relies on device retry until acked.                               | Non-2xx fails the activity → Temporal retries. Device should treat repeated POSTs of the same business id as idempotent.                                                    |
| **TCP**                        | `ACK <activityId>` written only after enqueue; `ERR …` otherwise. Relies on device retry until acked.                                                                       | Send fails unless the device answers `ACK` → Temporal retries. Raw TCP has no store-and-forward of its own.                                                                 |
| **TCP (custom wire protocol)** | Per-device/per-binding `tcpProtocol`: start/stop frame delimiters (MLLP-style, multiple frames per socket, per-frame ack-after-enqueue) and custom ACK/NAK reply templates. | Framed sends with a configurable expected ack (contains-match), or fire-and-forget for silent devices. See [TCP wire protocol](#tcp-wire-protocol-configurable-framing--acknak) below.                              |
| **FTP**                        | Store-and-forward: files persist in the drop folder until consumed (deleted) after a successful enqueue; failed files are re-swept on the next reconcile.                   | Upload uses temp-name-then-rename so the device never sees partial files; the deterministic filename (`{activityId}.json`) makes activity retries overwrite, not duplicate. |

Common to all: **`{messageType}-{businessId}`** (Workflow ID outbound, Activity ID inbound) collapses duplicates into one execution. Outbound sends run inside activities and must tolerate redelivery. There is deliberately **no local durable spool** — Temporal Cloud's SLA plus the SDK's auto-reconnecting channel cover transient unreachability; an offline proxy just means the work waits in Temporal and delivers on reconnect.

> **Persistent TCP sessions:** for real-time/industrial devices that need a _maintained_ socket with bidirectional heartbeats (not connect-per-message), a per-device persistent-session mode keeps the link warm while Temporal still does durable delivery (CLIENT or SERVER role, configurable liveness, correlated sends, unsolicited inbound → `DeliverToCloud`). Configure it per device under the Config tab's **Connection** section and watch per-device UP/DOWN in the **Persistent connections** table. Design + internals: [persistent-tcp-sessions.md](persistent-tcp-sessions.md). Demo it via the [reference stack](https://github.com/stephenmontague/durable-proxy-app-demo): `just run-dummy-edge-persistent` then `just demo-config-persistent`.

---

## TCP wire protocol (configurable framing + ACK/NAK)

Real devices frame TCP messages with start/stop characters (e.g. MLLP's `0x0B…0x1C 0x0D`) and use protocol-specific ack strings. A `tcpProtocol` block — on a device (default for all its TCP bindings) or on a single binding (override) — configures this; absent means legacy behavior (EOF framing, `ACK {id}\n`/`ERR …` replies, `ACK` expected outbound).

| Field | Meaning |
| ----- | ------- |
| `startDelimiter` | frame start (optional; requires `endDelimiter`) |
| `endDelimiter`   | frame end; unset = EOF-framed. When set, inbound connections are persistent: multiple frames per connection, each acked individually |
| `ackReply` / `nakReply` | inbound reply templates, sent **verbatim** after decoding; `{activityId}` / `{reason}` substituted — embed framing chars yourself if your protocol frames acks |
| `expectedAck`    | outbound: bytes that must appear **anywhere** in the device reply (so `ACK` matches a framed ack; beware a device that naks with `NACK` — use a distinguishing string) |
| `awaitReply`     | `false` = fire-and-forget (delivery weakens to "TCP write accepted") |

Strings use **WireString** escapes: printable ASCII, `\\` `\r` `\n` `\t` `\<` `\xHH`, and named control tokens `<NUL>`–`<US>` + `<DEL>` (full C0 set: `<STX>` `<ETX>` `<VT>` `<FS>` `<CR>` `<LF>` `<ACK>` `<NAK>` …). Identical parsers in Java ([`WireString.java`](../proxy/src/main/java/com/proxyapp/routing/WireString.java)) and the UI (`lib/wire-string.ts`, in the [reference stack](https://github.com/stephenmontague/durable-proxy-app-demo)).
