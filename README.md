# Cloud ↔ Edge Durable Proxy

A **domain-agnostic, durable connector** that bridges any **Cloud** application and any
**Edge** target (on-prem device, machine, or network) in both directions, with
**Temporal** as the durable backbone. See [PLAN.md](PLAN.md) for the full design.

## Repo layout

| Module                          | What it is                                                                 |
| ------------------------------- | -------------------------------------------------------------------------- |
| [`proxy/`](proxy/)              | The connector itself — the only Temporal worker, egress-only               |
| [`dummy-cloud/`](dummy-cloud/README.md) | Demo cloud app (:8091) — Temporal client, demo + control endpoints |
| [`dummy-edge/`](dummy-edge/README.md)   | Demo edge target (:8092, TCP 9001, FTP 2222) — auto-confirms       |
| [`management-ui/`](management-ui/README.md) | **Switchyard** operations console (:3000, Next.js) — lifecycle, message catalog, routing wizard, live Temporal feed |
| [`config/`](config/)            | Demo routing configs for hot-reload / validation demos                     |
| [`scripts/`](scripts/)          | `proxy-supervisor.sh` — restart-on-exit wrapper for remote restarts        |
| [`justfile`](justfile)          | Build + demo recipes (all run from this root)                              |

The root [pom.xml](pom.xml) is a Maven aggregator: `mvn package` builds all three apps.

## How it works

The proxy is the **only Temporal worker** and connects **egress-only** — no inbound
firewall ports on the customer side. Data and control both ride the same outbound gRPC
connection:

- **Cloud → Edge**: the cloud client starts a `DeliverToEdge` **workflow** (Workflow ID
  `{messageType}-{businessId}`, reuse `REJECT_DUPLICATE` — duplicates collapse to one
  execution); the proxy worker runs it, executing a `TransmitToDevice` activity —
  route → codec → connector → device channel.
- **Edge → Cloud**: the device pushes to a proxy ingress **channel** (HTTP path / TCP
  port / FTP folder). The channel — never the payload — identifies the message type. The
  proxy starts a `DeliverToCloud` standalone activity and acks the device only after
  Temporal accepted the enqueue.
- **Control plane**: a singleton `ProxyControlWorkflow` (Workflow ID `proxy-control`)
  holds desired state `{enabled, devices[], version}` plus lifecycle commands. The cloud
  drives it with signals; the proxy polls it via query, a `Reconciler` hot-applies changes
  (listeners, routes, worker polling) with no restart, and the proxy **reports its applied
  state back** so the cloud sees desired vs applied without ever reaching the proxy's
  network. `requestRestart`/`requestShutdown` signals make the proxy exit gracefully —
  paired with the supervisor wrapper, that's a remote restart over egress-only gRPC.

Everything domain-specific (message types, codecs, cloud endpoints, device templates)
starts in a **profile**; the **device-fleet** profile (cloud platform ↔ on-prem edge devices) ships as the reference demo.
The profile is only a **seed** — the message catalog is operational state in the control
workflow, so an operator defines their own message types (name, direction, codec, cloud
endpoint, dedup field) through the UI's **Catalog** tab with no code change or restart.
Payloads can be **json**, **xml**, or **raw** (opaque passthrough), chosen per type.

## Prerequisites

- Java 17+ (21 recommended), Maven, [`just`](https://github.com/casey/just), Temporal CLI **v1.7.0+**
- A local Temporal server on `localhost:7233` with **Server 1.31+** and the
  `activity.enableStandalone` dynamic config flag (required for Standalone Activities).
  The always-on Docker stack in `~/git/temporal/docker-compose.yml` provides this
  (`temporalio/server:1.31.1`, Web UI at <http://localhost:8080>); without Docker,
  `just temporal-dev` starts an equivalent CLI dev server.

## Run the demo (3 terminals, all from this root)

```sh
just temporal-check      # verify the Docker Temporal is up + standalone-capable
just run-proxy           # 1: the proxy        (:8090, worker on proxy-main/proxy-control)
just run-dummy-cloud     # 2: dummy cloud app  (:8091, Temporal client only)
just run-dummy-edge      # 3: dummy edge target(:8092 + TCP 9001 + FTP 2222)
```

> Use `just run-proxy-managed` instead of `run-proxy` to run the proxy under the
> restart-on-exit supervisor — required for the management UI's RESTART button.

### Management UI (optional 4th terminal)

```sh
just run-ui              # Switchyard console at http://localhost:3000
```

Lifecycle control (enable/disable/restart/shutdown), a guided device-config wizard with
validation, a live feed of message workflows/activities with event history, and demo
dispatch — all through Temporal signals/queries. **The UI never talks to the proxy
directly**, so the on-prem firewall only ever passes the single egress gRPC connection.

Then:

```sh
just demo-command            # DEVICE_COMMAND → /commands → COMMAND_RESULT → cloud
just demo-config-tcp     # CONFIG_UPDATE → TCP 9001 → CONFIG_ACK → TCP 6001
just demo-report-ftp # REPORT_REQUEST → FTP folder → REPORT_UPLOAD → FTP folder
just demo-idempotency     # duplicate dispatch collapses to one execution
just demo-disable         # remote soft-off (ingress stops, outbound pauses, egress stays up)
just demo-enable          # remote resume
just demo-apply-config    # hot routing reload (config/sample-routes.json), no restart
just demo-apply-bad-config# out-of-pool port → rejected with a clear message
just demo-catalog         # define a custom message type at runtime (xml codec), no restart
just demo-command-http-xml   # XML round trip: device emits XML, xml codec extracts the id
                          #   (needs: just run-dummy-edge-xml)
just demo-state           # control workflow state (via cloud → Temporal query)
just proxy-status         # proxy's locally applied state (listeners, routes)
```

Inspect executions in the Temporal UI at <http://localhost:8080> (standalone activities
have their own nav item; the UI is at <http://localhost:8233> when using the
`just temporal-dev` fallback instead of Docker).

> **Ports:** the demo apps use 8090/8091/8092 to stay clear of the Docker Temporal UI
> (8080) and other local stacks (see PLAN.md appendix). Everything is overridable via
> standard Spring env vars, e.g.
> `SERVER_PORT=9090 SPRING_TEMPORAL_CONNECTION_TARGET=127.0.0.1:7243 just run-proxy`.

## Targeting Temporal Cloud

Activate the `cloud` Spring profile and provide the install's bootstrap credentials
(namespace-per-install keeps the blast radius to that customer's own namespace):

```sh
TEMPORAL_TARGET=<ns-id>.<region>.tmprl.cloud:7233 \
TEMPORAL_NAMESPACE=<tenant>.<account-id> \
TEMPORAL_KEY_FILE=/path/client.key TEMPORAL_CERT_FILE=/path/client.pem \
java -jar proxy/target/proxy-app-*.jar --spring.profiles.active=cloud
```

## Architecture map (proxy module)

```
proxy/src/main/java/com/proxyapp/
├── config/        ProxyProperties (bootstrap), ProxyAppConfig (wiring), ActivityClient bean
├── routing/       MessageType, Direction, Channel, RouteBinding, EdgeConfig, MessageCatalog,
│                  RouteTable, ConfigValidator, DeviceTemplate, RoutingState,
│                  MessageTypeResolver SPI + FilenamePatternResolver (opt-in multi-type channels)
├── profile/       Profile SPI, DeviceFleetProfile (reference), ProfileRegistry
├── codec/         MessageCodec SPI, JsonCodec/XmlCodec/RawCodec, CodecRegistry
├── connector/     Connector SPI, Http/Tcp/FtpConnector, ChannelTarget, ConnectorFactory
├── temporal/      workflow/ DeliverToEdgeWorkflow (cloud→edge dispatch)
│                  activity/ DeliverToEdgeActivity ("TransmitToDevice"), DeliverToCloudActivity
├── ingress/       InboundGateway (channel→type→decode→enqueue→ack),
│                  HttpIngressController, TcpSocketServer, FtpIngressListener, AdminController
└── control/       ProxyControlWorkflow(+Impl), ProxyControlStarter, ProxyControlPoller, Reconciler,
                   CatalogEntryDto + CatalogValidator (operator-editable message catalog)
```

## Per-transport reliability profile

| Transport | Inbound (edge → proxy) | Outbound (proxy → edge) |
| --------- | ---------------------- | ----------------------- |
| **HTTP**  | Device gets `202` only after Temporal accepted the enqueue (`503` while disabled, `404` unbound channel). Relies on device retry until acked. | Non-2xx fails the activity → Temporal retries. Device should treat repeated POSTs of the same business id as idempotent. |
| **TCP**   | `ACK <activityId>` written only after enqueue; `ERR …` otherwise. Relies on device retry until acked. | Send fails unless the device answers `ACK` → Temporal retries. Raw TCP has no store-and-forward of its own. |
| **TCP (custom wire protocol)** | Per-device/per-binding `tcpProtocol` config: start/stop frame delimiters (MLLP-style persistent connections, multiple frames per socket, per-frame ack-after-enqueue) and custom ACK/NAK reply templates. | Framed sends with a configurable expected ack (contains-match), or fire-and-forget for silent devices. See the PLAN.md wire-protocol appendix; demo: `just demo-config-tcp-framed`. |
| **FTP**   | Inherently store-and-forward: files persist in the drop folder until consumed (deleted) after a successful enqueue; failed files are re-swept on the next reconcile. | Upload uses temp-name-then-rename so the device never sees partial files; the deterministic filename (`{activityId}.json`) makes activity retries overwrite, not duplicate. |

Common to all: **Activity ID = `{messageType}-{businessId}`** collapses duplicate cloud
dispatches and duplicate edge pushes into one execution (exactly-once delivery on top of
at-least-once activities). Outbound sends run inside activities and must tolerate
redelivery. There is deliberately **no local durable spool** — Temporal Cloud's SLA plus
the SDK's auto-reconnecting channel cover transient unreachability, and an offline proxy
just means the work waits in Temporal and delivers on reconnect.

## Tests

```sh
just test      # Java: routing core, validators, codecs, TCP wire protocol, catalog signals
just test-ui   # TypeScript: WireString + config + catalog validator parity vectors (vitest)
```

## License

[MIT](LICENSE)
