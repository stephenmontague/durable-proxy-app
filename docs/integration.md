# Integrating your cloud app

How to wire your own cloud application to the proxy, and the message-type / device model you configure at runtime. For orientation and quickstart see the [README](../README.md); for design internals see [internals.md](internals.md).

---

## Make your cloud app a Temporal client

Your cloud app integrates with the proxy **by contract, not by shared code**: it connects to the same Temporal namespace and speaks a handful of agreed names plus wire-compatible JSON. **Any Temporal SDK works** — Go, Python, TypeScript, Java, .NET, PHP, Ruby — so the cloud app can be in whatever language and framework you already use. The four pieces below _are_ the contract; the collapsed snippets come from the reference impl ([`dummy-cloud/`](https://github.com/stephenmontague/durable-proxy-app-demo/tree/main/dummy-cloud), Java/Spring) and are illustrative, not prescriptive.

### 1. Connect (a client, not a worker)

Create a Temporal client for your **namespace** and **server address** (a local server or Temporal Cloud). The cloud app registers **no workflows or activities** and runs **no worker** — it only starts workflows and sends signals/queries.

For **Temporal Cloud**, target `<ns-id>.<region>.tmprl.cloud:7233` with namespace `<tenant>.<account-id>` and your client mTLS cert/key (every SDK exposes a TLS/credentials option). The reference app connects without mTLS for local dev; see [Target Temporal Cloud](../README.md#target-temporal-cloud).

<details> <summary>Reference (Java/Spring) — <a href="https://github.com/stephenmontague/durable-proxy-app-demo/blob/main/dummy-cloud/src/main/java/com/dummycloud/TemporalClientConfig.java"><code>TemporalClientConfig.java</code></a></summary>

```java
@Bean(destroyMethod = "shutdown")
public WorkflowServiceStubs serviceStubs(CloudProperties p) {
    return WorkflowServiceStubs.newServiceStubs(
        WorkflowServiceStubsOptions.newBuilder().setTarget(p.temporal().target()).build());
}

@Bean
public WorkflowClient workflowClient(WorkflowServiceStubs stubs, CloudProperties p) {
    return WorkflowClient.newInstance(stubs,
        WorkflowClientOptions.newBuilder().setNamespace(p.temporal().namespace()).build());
}
```

</details>

### 2. Dispatch Cloud → Edge

Start a workflow with these parameters; it lands in Temporal and the proxy worker runs it:

| Parameter       | Value                                                                                                                                                  |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Workflow type   | `DeliverToEdge`                                                                                                                                        |
| Task queue      | `proxy-main`                                                                                                                                           |
| Workflow ID     | `{messageType}-{businessId}` — e.g. `DEVICE_COMMAND-CMD-1001`                                                                                          |
| ID reuse policy | `REJECT_DUPLICATE` — a re-dispatch of the same business id collapses to one execution                                                                  |
| Input (JSON)    | `{ "messageType": "…", "businessId": "…", "payload": "…" }` — `payload` is the codec's wire string (JSON/XML/raw); the proxy encodes it for the device |

<details> <summary>Reference (Java) — <a href="https://github.com/stephenmontague/durable-proxy-app-demo/blob/main/dummy-cloud/src/main/java/com/dummycloud/OutboundDispatcher.java"><code>OutboundDispatcher.java</code></a></summary>

```java
WorkflowOptions options = WorkflowOptions.newBuilder()
    .setWorkflowId(messageType + "-" + businessId)            // e.g. DEVICE_COMMAND-CMD-1001
    .setTaskQueue("proxy-main")
    .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
    .build();
WorkflowStub stub = workflowClient.newUntypedWorkflowStub("DeliverToEdge", options);
stub.start(new CanonicalMessage(messageType, businessId, payload));
// catch WorkflowExecutionAlreadyStarted -> this dispatch was a duplicate
```

</details>

### 3. Receive Edge → Cloud

Expose one HTTP endpoint per inbound (`EDGE_TO_CLOUD`) type, at the path you set as that type's **cloud endpoint**. The proxy's `DeliverToCloud` activity POSTs the JSON body `{ messageType, businessId, payload }` to it; your handler must **return 2xx** and be **idempotent** (the proxy retries the same `businessId` until it gets a 2xx). It's plain HTTP — any web framework.

<details> <summary>Reference (Java/Spring) — <a href="https://github.com/stephenmontague/durable-proxy-app-demo/blob/main/dummy-cloud/src/main/java/com/dummycloud/InboundController.java"><code>InboundController.java</code></a></summary>

```java
@PostMapping("/api/command-result")          // = this type's cloud endpoint
public Map<String,String> commandResult(@RequestBody CanonicalMessage msg) {
    // ... handle it ...
    return Map.of("status", "received");      // any 2xx; non-2xx makes Temporal retry
}
```

</details>

### 4. Drive the control plane

Operator changes are **Updates** to the `proxy-control` workflow: each one validates, mutates, and **returns the resulting `ProxyControlState` synchronously**, so you read the outcome straight from the return value — **accepted** (`version` bumped, `lastError` null) or **rejected** (`lastError` set, `version` unchanged). No confirmation Query. The remaining verbs — `requestReconcile` (re-apply now) and the `requestRestart` / `requestShutdown` lifecycle commands — are fire-and-forget **signals**.

<details> <summary>Reference (Java) — <a href="https://github.com/stephenmontague/durable-proxy-app-demo/blob/main/dummy-cloud/src/main/java/com/dummycloud/ConfigStateService.java"><code>ConfigStateService.java</code></a></summary>

```java
WorkflowStub control = workflowClient.newUntypedWorkflowStub("proxy-control");
// Config changes are Updates — validate + mutate + return the new state in one call.
JsonNode after = control.update("upsertMessageType", JsonNode.class, entry);  // or enable, applyConfig, ...
boolean accepted = after.get("lastError").isNull();          // version bumped when accepted
```

</details>

| Name                                      | Kind   | Payload → returns                          | Effect                                                                                                                                |
| ----------------------------------------- | ------ | ------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------- |
| `enable` / `disable`                      | Update | — → state                                  | Soft on/off of the data plane (listeners + outbound; control stays up)                                                                |
| `applyConfig`                             | Update | `EdgeConfig[]` → state                     | Replace the full device/routing config                                                                                                |
| `upsertDevice` / `removeDevice`           | Update | `EdgeConfig` / `deviceId` → state          | Add-or-replace / remove one device                                                                                                    |
| `upsertMessageType` / `removeMessageType` | Update | `CatalogEntryDto` / `type` → state         | Add-or-replace / remove one message type                                                                                              |
| `importCatalog`                           | Update | `CatalogEntryDto[]` → state                | Replace the whole message catalog                                                                                                     |
| `requestReconcile`                        | Signal | —                                          | Re-apply desired state now (manual repair / boot sync / drift self-heal)                                                              |
| `requestRestart` / `requestShutdown`      | Signal | —                                          | Graceful proxy exit (restart relaunches via supervisor)                                                                               |
| `ackLifecycle`                            | Signal | `requestId` (string)                       | **Sent by the proxy** — clears a lifecycle command durably before it exits                                                            |
| `reportApplied`                           | Update | `AppliedStatus` → desired `version` (long) | **Sent by the proxy** — pushes link-health transitions between reconciles; the return lets it detect drift                            |
| `getState`                                | Query  | → `ProxyControlState`                      | Desired state `{enabled, devices, catalogEntries, typeDirections, version, lastError, applied}` — hydrates the read model, not polled |

> **Tip — don't query Temporal on every UI read.** Temporal Queries are billable Actions. The reference app persists each _accepted_ state (the Update's return value) to a local H2 read model and serves UI reads from there (`workflow → H2`, one-way); it issues a `getState` Query only **once**, to hydrate an empty read model. The **Switchyard** UI never talks to the proxy directly — every command is an Update/signal, every readout comes from the read model.

### The contract (works from any language)

You do **not** need to depend on the proxy module — define the shapes natively in your language:

- **Temporal names:** workflow type `DeliverToEdge`, control workflow ID `proxy-control`, task queues `proxy-main` (data) and `proxy-control` (control); the update/signal/query names above.
- **Wire-compatible JSON:** match the field names — serialization is by field name with no language- or class-metadata on the wire, so a Go struct, a Python dataclass, or a TS interface all interop. The shapes: `CanonicalMessage {messageType, businessId, payload}`, `CatalogEntryDto`, `EdgeConfig` (+ `RouteBinding`, `Channel`), `ProxyControlState`, `AppliedStatus` — their fields are in [Message Types & Devices](#message-types--devices).

---

## Message Types & Devices

Two things you configure: **what** can be routed (message types / the catalog) and **where** it goes (devices / their bindings). Both are operational data in the control workflow — editable at runtime.

### Message Types (the catalog)

A message type is a catalog entry (`CatalogEntryDto`) defined entirely as data — adding or editing one is a signal, **no code change or redeploy**:

```json
{
  "type": "DIAGNOSTICS_UPLOAD",
  "direction": "EDGE_TO_CLOUD",
  "codec": "xml",
  "cloudEndpoint": "/api/diagnostics-upload",
  "businessIdField": "snapshotId"
}
```

| Field               | Meaning                                                                                                                                                                                                                           |
| ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **type**            | The message-type name (e.g. `DEVICE_COMMAND`) — the routing key.                                                                                                                                                                  |
| **Direction**       | `CLOUD_TO_EDGE` (cloud initiates → proxy runs a `DeliverToEdge` workflow → device) or `EDGE_TO_CLOUD` (device initiates → proxy runs a `DeliverToCloud` activity → your cloud endpoint).                                          |
| **Codec**           | `json`, `xml`, or `raw`. `raw` is opaque passthrough — the proxy carries bytes and never parses them.                                                                                                                             |
| **Cloud Endpoint**  | **`EDGE_TO_CLOUD` only.** The path on your cloud app the proxy POSTs inbound messages to (e.g. `/api/command-result`), appended to the proxy's configured cloud base URL. Lives on the _cloud_; it is **not** the device channel. |
| **Business ID**     | The field (`businessIdField`) the codec reads out of the decoded payload to form the dedup key `{type}-{businessId}`. If absent/unparseable (or `raw`), the proxy falls back to a content hash.                                   |
| **allowDuplicates** | When `true`, each push gets a unique id (UUID-suffixed) instead of deduping — for event/telemetry streams where two identical frames are two real observations. Default `false`.                                                  |

### Devices (routing)

A **Device** is a configured edge target (`EdgeConfig`): a `deviceId`, connection coordinates (`baseUrl` for HTTP, `host`/FTP creds, optional default `tcpProtocol`, optional persistent `tcpSession`), and a list of **bindings**. Each **RouteBinding** maps one message type to a wire and a coordinate:

- **Via** = the **Transport** for this binding: `HTTP`, `TCP`, or `FTP`.
- **Channel** = the **device↔proxy coordinate** for this binding — a `ChannelKind` (`PORT`, `PATH`, or `FOLDER`) plus a value.

A `DeviceTemplate` is a clone-and-fill profile for a device model (supply only site values like host

- base port); the Switchyard wizard builds bindings for you.

### channel vs cloud endpoint (the one to get right)

These are the two coordinates operators most often confuse — the UI calls them out explicitly (see [`channel-copy.ts`](https://github.com/stephenmontague/durable-proxy-app-demo/blob/main/management-ui/src/lib/channel-copy.ts) / [`flow-legend.tsx`](https://github.com/stephenmontague/durable-proxy-app-demo/blob/main/management-ui/src/components/catalog/flow-legend.tsx)):

```
EDGE_TO_CLOUD:   device ──[ channel ]──▶ proxy ──[ cloud endpoint ]──▶ cloud
CLOUD_TO_EDGE:   cloud  ─────────────▶ proxy ──[ channel ]──────────▶ device
```

- **channel** — device↔proxy coordinate, set **per device binding**. Inbound it's a path/port/folder _on the proxy_ the device reaches (e.g. `/command-result`, port `6001`); outbound it's the path/port/folder _on the device_ the proxy delivers to (e.g. `/commands`).
- **cloud endpoint** — proxy→cloud path, set **per message type**, **inbound only** (e.g. `/api/command-result`). It lives on the cloud and is independent of any device's channel.
