# Demo: two installs, one binary — completely different clients

The pitch in one screen: the **same** `proxy.jar` (and the same `dummy-cloud` / `dummy-edge`),
installed twice, bent to two clients that share nothing — different industry, payload, wire
framing, and heartbeat cadence — with **zero code changes**. Only the applied config differs, and
each install is isolated in its own Temporal namespace (exactly how you'd ship one per customer).

|                | **Sandbox A — warehouse sortation** | **Sandbox B — utility / smart-grid** |
| -------------- | ----------------------------------- | ------------------------------------ |
| Namespace      | `sandbox-a`                         | `sandbox-b`                          |
| Message types  | `DIVERT_COMMAND` ↓ · `SCAN_EVENT` ↑ | `SETPOINT_UPDATE` ↓ · `METER_READING` ↑ |
| Payload / codec| **CSV** / `raw`                     | **XML** / `xml`                      |
| Framing        | `STX` / `ETX`                       | `<start>` / `end`                    |
| Link           | persistent CLIENT, **10s** heartbeat| persistent CLIENT, **30s** heartbeat |
| Device telemetry | emits a CSV `SCAN_EVENT` every 8s | emits an XML `METER_READING` every 12s |
| Inbound dedup  | `allowDuplicates` **on** — every scan delivered | dedup **on** (default) — identical readings collapse |
| Ports          | proxy 8090 · cloud 8091 · edge 8092 · **UI 3000** | proxy 8190 · cloud 8191 · edge 8192 · **UI 3001** |

> The device frames + emits its own native payload over the kept-alive socket; the proxy types
> each frame via the session's `inboundType` and starts a durable `DeliverToCloud`. So the two
> payloads flow with no dummy-cloud changes — the warehouse box "speaks CSV," the meter "speaks XML."

## Prerequisites
- The shared Docker Temporal on `localhost:7233` (or `just temporal-dev`).
- **Stop the default demo stack first** if it's running — Sandbox A reuses ports 8090–8092.
- Build once: `just build` and (for two UIs) `just build-ui`.
- Create the namespaces: `just sandbox-namespaces`.

> **Installs boot empty.** The proxy starts with no catalog and no devices (the `empty` profile) —
> you configure each install through its Switchyard UI, and it persists in the cloud app's H2 read
> model **and** the control workflow (survives restarts; the UI's config reads cost no Temporal
> Actions). `sandbox-apply` below is just a fast way to seed a sandbox's config through the same
> control API, so you don't have to click it all in for the demo.

> **Start the two UIs sequentially** (bring Sandbox A fully up before Sandbox B). Next 16's
> `next start` reads `TEMPORAL_NAMESPACE` + `DUMMY_CLOUD_URL` only from `management-ui/.env.local`
> (not command-line env vars), so `run-ui-ns` writes that file and each UI captures it at *its own*
> startup — overlapping starts would race on the shared file. The third arg is the install's cloud port.

## Bring up Sandbox A (warehouse)
Four terminals, then apply its config:
```sh
just run-proxy-ns          sandbox-a 8090
just run-cloud-ns          sandbox-a 8091
just run-dummy-edge-sandbox-a
just run-ui-ns             sandbox-a 3000 8091
just sandbox-apply         sandbox-a 8091      # quick-seed sorter-07's config (or build it in the UI — either persists in H2)
```

## Bring up Sandbox B (smart-grid)
Four more terminals (offset ports), then apply:
```sh
just run-proxy-ns          sandbox-b 8190
just run-cloud-ns          sandbox-b 8191
just run-dummy-edge-sandbox-b
just run-ui-ns             sandbox-b 3001 8191
just sandbox-apply         sandbox-b 8191      # quick-seed meter-gw-12's config (or build it in the UI)
```

## What to look at
Open both consoles side by side — **<http://localhost:3000>** (A) and **<http://localhost:3001>** (B):

- **Config tab** — two completely different catalogs and devices. A: `DIVERT_COMMAND`/`SCAN_EVENT`
  on the `raw` codec, `sorter-07` with `STX`/`ETX` framing + a 10s heartbeat. B: `SETPOINT_UPDATE`/
  `METER_READING` on the `xml` codec, `meter-gw-12` with `<start>`/`end` framing + a 30s heartbeat.
- **Console → Persistent connections** — each device shows **UP** (CLIENT), heartbeating at its own
  cadence. Kill a `run-dummy-edge-sandbox-*` terminal and watch that one flip **DOWN**; restart it
  and it reconnects.
- **Console → Recent traffic** — `SCAN_EVENT` activities tick over in A, `METER_READING` in B, as
  each device emits its telemetry over the live socket.
- **The terminals breathe** — both sides trace the heartbeat on a dedicated `heartbeat` logger that
  is **off by default** (`logging.level.heartbeat: OFF` in each `application.yml`) and flipped on for
  the demo: the proxy via the `run-proxy-ns` recipe (`--logging.level.heartbeat=INFO`), the edge via
  its `persistent`/`sandbox-*` profiles. You'll see the edge log `<- PING   -> PONG` and the proxy
  log `sorter-07 -> PING #N` / `<- PONG #N (link up …)` — the climbing `#N` is the live proof the
  socket is held open, not reopened per message. To silence it anywhere, set `logging.level.heartbeat`
  back to `OFF`; to see it on a normal `run-proxy`, pass `--logging.level.heartbeat=INFO`.
- **The actual payloads** (verbatim CSV vs XML) on each cloud:
  ```sh
  curl -s localhost:8091/demo/confirms | jq .   # A: "SCAN,PKG-44821,LANE-3,..."
  curl -s localhost:8191/demo/confirms | jq .   # B: "<reading><meter>M-9001</meter>...</reading>"
  ```

## Notes
- **Heartbeat ≠ telemetry**: the heartbeat ping (10s / 30s) keeps the link alive; the telemetry emit
  (8s / 12s) is the device pushing business data. They're independent knobs.
- **Dedup vs. every-event** — the `allowDuplicates` flag: both devices emit a *fixed* payload every
  interval. Sandbox **A**'s `SCAN_EVENT` sets `allowDuplicates: true`, so every scan is delivered —
  the **Recent traffic** feed ticks continuously even though the bytes never change (each push gets a
  unique activity id). Sandbox **B**'s `METER_READING` leaves it off (the default), so identical
  readings **dedup to a single `DeliverToCloud`** — exactly-once in action; the feed shows one. Flip
  the flag in the Config tab (or the catalog JSON) to swap either device's behavior. Use dedup when
  the business id is a real key (an order/command id); use `allowDuplicates` for event/telemetry
  streams where each push is its own observation.
- **Delimiter vs payload collisions**: `end` is a plain word, so a payload containing `end` would
  frame early — fine for these demo payloads, but a real deployment would pick a sentinel that can't
  occur in the body (this is the kind of thing the operator tunes per client, in config).
- **Teardown**: Ctrl-C the eight terminals. Namespaces persist; re-running is idempotent.
