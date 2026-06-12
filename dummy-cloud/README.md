# dummy-cloud

Test-harness **cloud app** for the [proxy](../proxy) running the Warehouse
reference profile. A Temporal **client only** — it runs no worker.

- `POST /demo/command | /demo/config | /demo/report` — start a `DeliverToEdge`
  standalone activity (Activity ID `{type}-{businessId}`, dedup policies set).
- `POST /api/command-result | /api/config-ack | /api/report-upload` — receive
  the proxy's `DeliverToCloud` posts; `GET /demo/confirms` lists them.
- `POST /control/enable | /control/disable | /control/apply-config`,
  `GET /control/state` — drive the proxy's `ProxyControlWorkflow` via signals/query.

Run from the repo root: `just run-dummy-cloud` (port **8091**). Ports/targets
overridable via `SERVER_PORT`, `CLOUD_TEMPORAL_TARGET`.
