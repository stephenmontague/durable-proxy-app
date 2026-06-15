# Cloud <-> Edge Proxy monorepo — build & demo recipes
# Local dev targets the always-on Docker Temporal at localhost:7233
# (~/git/temporal/docker-compose.yml — Server 1.31+ with activity.enableStandalone=true,
# Web UI at http://localhost:8080). `just temporal-dev` is the no-Docker fallback.
# Requires: just, Java 17+, Maven, Temporal CLI (v1.7.0+).
#
# Modules: proxy/ (the connector), dummy-cloud/ + dummy-edge/ (demo harness).
# All recipes run from the repo root; the aggregator pom builds everything.

set shell := ["bash", "-cu"]

# Ports (keep in sync with PLAN.md > Appendix).
# 809x keeps clear of the Docker Temporal UI (8080) and other local dev stacks.
proxy_admin_port := "8090"
cloud_port       := "8091"
edge_port        := "8092"
temporal_ui      := "http://localhost:8080"

# Show available recipes
default:
    @just --list

# ---------------------------------------------------------------------------
# Build & test (reactor: all three modules)
# ---------------------------------------------------------------------------

# Build everything (proxy + dummies)
build:
    mvn -q clean package

# Run unit tests (routing core, codecs, validators, control workflow)
test:
    mvn -q test

# Run the management UI's unit tests (WireString/validator parity with the Java side)
test-ui:
    @[ -d management-ui/node_modules ] || (cd management-ui && npm install)
    cd management-ui && npm test

# Compile without packaging
compile:
    mvn -q compile

# Remove build output
clean:
    mvn -q clean

# ---------------------------------------------------------------------------
# Local Temporal
# ---------------------------------------------------------------------------

# Verify the local Temporal server (Docker, localhost:7233) is up and standalone-activity
# capable: needs Server 1.31+ and the activity.enableStandalone dynamic config flag.
temporal-check:
    @temporal operator cluster system | head -2
    @temporal activity start --type HealthCheck --activity-id just-temporal-check \
        --task-queue temporal-check-q --start-to-close-timeout 1s \
        --schedule-to-close-timeout 2s --input '"ping"' > /dev/null \
        && echo "OK: standalone activities are enabled"

# Fallback when the Docker stack isn't running: a CLI dev server on the same port with
# the standalone-activity flag enabled (Web UI at http://localhost:8233 in this case).
temporal-dev:
    temporal server start-dev \
        --dynamic-config-value activity.enableStandalone=true

# Quick health check against the local server
temporal-status:
    temporal operator namespace list

# ---------------------------------------------------------------------------
# Run the components (each in its own terminal, from the repo root)
# ---------------------------------------------------------------------------

# Run the proxy against the local dev server (Spring profile: local)
run-proxy:
    mvn -q -pl proxy spring-boot:run -Dspring-boot.run.profiles=local

# Run the proxy under a restart-on-exit supervisor. Required for the management UI's
# RESTART button: the proxy exits with code 10 and the wrapper relaunches it.
run-proxy-managed:
    mvn -q -pl proxy package -DskipTests
    ./scripts/proxy-supervisor.sh

# Run the dummy cloud app
run-dummy-cloud:
    mvn -q -pl dummy-cloud spring-boot:run -Dspring-boot.run.profiles=local

# Run the dummy edge target
run-dummy-edge:
    mvn -q -pl dummy-edge spring-boot:run -Dspring-boot.run.profiles=local

# Run the dummy edge speaking MLLP-style framed TCP (<VT>...<FS><CR>, framed acks).
# Pair with: just demo-apply-config config/framed-routes.json
run-dummy-edge-framed:
    mvn -q -pl dummy-edge spring-boot:run -Dspring-boot.run.profiles=local,framed

# Run the dummy edge speaking XML instead of JSON. Pair with: just demo-command-http-xml
run-dummy-edge-xml:
    mvn -q -pl dummy-edge spring-boot:run -Dspring-boot.run.profiles=local,xml

# Run the dummy edge as a persistent-session device (listens on 9100; the proxy dials in and
# keeps the socket warm with PING/PONG heartbeats). Pair with: just demo-config-persistent
run-dummy-edge-persistent:
    mvn -q -pl dummy-edge spring-boot:run -Dspring-boot.run.profiles=local,persistent

# ---------------------------------------------------------------------------
# Multi-sandbox demo: the SAME binaries installed twice, bent to two different clients
# purely by config (full runbook: docs/multi-sandbox-demo.md).
#   A = warehouse   CSV / STX-ETX / 10s   · ns sandbox-a · proxy 8090 cloud 8091 edge 8092 UI 3000
#   B = smart-grid  XML / <start>-end / 30s · ns sandbox-b · proxy 8190 cloud 8191 edge 8192 UI 3001
# ---------------------------------------------------------------------------

# Create the two Temporal namespaces (safe to re-run; ignores "already exists")
sandbox-namespaces:
    -temporal operator namespace create --namespace sandbox-a --retention 24h 2>/dev/null || true
    -temporal operator namespace create --namespace sandbox-b --retention 24h 2>/dev/null || true
    @temporal operator namespace list | grep -E "Name:.*sandbox-[ab]" || echo "(check: namespaces sandbox-a / sandbox-b)"

# Proxy for a sandbox — same jar, different namespace + port. Flips on the heartbeat trace
# (logging.level.heartbeat=INFO) so the terminal shows the link breathing. e.g. just run-proxy-ns sandbox-a 8090
run-proxy-ns ns port:
    mvn -q -pl proxy spring-boot:run -Dspring-boot.run.profiles=local \
        -Dspring-boot.run.arguments="--server.port={{port}} --spring.temporal.namespace={{ns}} --logging.level.heartbeat=INFO"

# Dummy cloud for a sandbox. e.g. just run-cloud-ns sandbox-a 8091
# NOTE: dummy-cloud has its own Temporal client (not the Spring starter), so its namespace key is
# `cloud.temporal.namespace` — NOT `spring.temporal.namespace` (that's the proxy's). Using the wrong
# one leaves the cloud on the `default` namespace and every /control/* call 500s (WorkflowNotFound).
run-cloud-ns ns port:
    mvn -q -pl dummy-cloud spring-boot:run -Dspring-boot.run.profiles=local \
        -Dspring-boot.run.arguments="--server.port={{port}} --cloud.temporal.namespace={{ns}}"

# Dummy edge for each sandbox (framing + telemetry baked into the Spring profile)
run-dummy-edge-sandbox-a:
    mvn -q -pl dummy-edge spring-boot:run -Dspring-boot.run.profiles=local,sandbox-a
run-dummy-edge-sandbox-b:
    mvn -q -pl dummy-edge spring-boot:run -Dspring-boot.run.profiles=local,sandbox-b

# Management UI for a sandbox (uses `next start`, so two can run at once — run `just build-ui` first).
# e.g. just run-ui-ns sandbox-a 3000
# NOTE: Next 16's `next start` does NOT forward command-line env vars (TEMPORAL_NAMESPACE=... npx ...)
# to its server worker — only `.env*` files reach it. So we write the namespace into .env.local, which
# each `next start` reads at its own startup. Bring sandboxes up SEQUENTIALLY (A fully, then B): each
# worker captures .env.local when it boots, so the shared file is fine for the documented flow.
run-ui-ns ns port:
    cd management-ui && printf 'TEMPORAL_NAMESPACE=%s\n' {{ns}} > .env.local && exec npx next start -p {{port}}

# Apply a sandbox's config to its cloud: clears the seeded device, imports the catalog, applies the
# device. e.g. just sandbox-apply sandbox-a 8091
sandbox-apply name cloud_port:
    -curl -fsS -X POST localhost:{{cloud_port}}/control/remove-device/edge-gateway-01 > /dev/null 2>&1 || true
    curl -fsS -X POST localhost:{{cloud_port}}/control/import-catalog \
        -H 'content-type: application/json' --data-binary @config/{{name}}-catalog.json | jq -c '.state.typeDirections'
    curl -fsS -X POST localhost:{{cloud_port}}/control/apply-config \
        -H 'content-type: application/json' --data-binary @config/{{name}}-routes.json | jq -c '[.state.devices[].deviceId]'

# Run the management UI (Next.js dev server on http://localhost:3000)
run-ui:
    @[ -d management-ui/node_modules ] || (cd management-ui && npm install)
    cd management-ui && npm run dev

# Production build of the management UI
build-ui:
    @[ -d management-ui/node_modules ] || (cd management-ui && npm install)
    cd management-ui && npm run build

# ---------------------------------------------------------------------------
# Demo (assumes: Temporal on 7233, run-proxy, run-dummy-cloud, run-dummy-edge are up)
# ---------------------------------------------------------------------------

# End-to-end HTTP round trip: DEVICE_COMMAND (cloud->edge) then COMMAND_RESULT (edge->cloud)
demo-command:
    @echo ">> Triggering DEVICE_COMMAND via dummy-cloud ..."
    curl -fsS -X POST localhost:{{cloud_port}}/demo/command \
        -H 'content-type: application/json' \
        -d '{"commandId":"CMD-1001","action":"REBOOT"}' | jq .
    @echo ">> Inspect both standalone activities in the Temporal UI: {{temporal_ui}}"
    @sleep 2
    @echo ">> Check dummy-cloud received the COMMAND_RESULT:"
    curl -fsS localhost:{{cloud_port}}/demo/confirms | jq .

# TCP round trip: CONFIG_UPDATE (cloud->edge, device port 9001) then
# CONFIG_ACK (edge->cloud, proxy port 6001)
demo-config-tcp:
    @echo ">> Triggering CONFIG_UPDATE via dummy-cloud ..."
    curl -fsS -X POST localhost:{{cloud_port}}/demo/config \
        -H 'content-type: application/json' \
        -d '{"configId":"CFG-2001","key":"reportingIntervalSec","value":30}' | jq .
    @sleep 2
    @echo ">> Check dummy-cloud received the CONFIG_ACK:"
    curl -fsS localhost:{{cloud_port}}/demo/confirms | jq .

# FTP round trip: REPORT_REQUEST (cloud->edge, device folder report-requests) then
# REPORT_UPLOAD (edge->cloud, proxy folder report-uploads)
demo-report-ftp:
    @echo ">> Triggering REPORT_REQUEST via dummy-cloud ..."
    curl -fsS -X POST localhost:{{cloud_port}}/demo/report \
        -H 'content-type: application/json' \
        -d '{"reportId":"RPT-3001","kind":"daily-metrics"}' | jq .
    @sleep 3
    @echo ">> Check dummy-cloud received the REPORT_UPLOAD:"
    curl -fsS localhost:{{cloud_port}}/demo/confirms | jq .

# Remotely DISABLE this install via the control workflow (soft off)
demo-disable:
    curl -fsS -X POST localhost:{{cloud_port}}/control/disable | jq .

# Remotely ENABLE this install via the control workflow
demo-enable:
    curl -fsS -X POST localhost:{{cloud_port}}/control/enable | jq .

# Push a routing config update (hot reload, no restart)
demo-apply-config file="config/sample-routes.json":
    curl -fsS -X POST localhost:{{cloud_port}}/control/apply-config \
        -H 'content-type: application/json' \
        --data-binary @{{file}} | jq .

# TCP round trip over a CUSTOM wire protocol (MLLP-style framing + framed acks).
# Requires dummy-edge running with the framed profile: just run-dummy-edge-framed
demo-config-tcp-framed:
    @echo ">> Applying MLLP wire-protocol config (hot, no restart) ..."
    curl -fsS -X POST localhost:{{cloud_port}}/control/apply-config \
        -H 'content-type: application/json' \
        --data-binary @config/framed-routes.json | jq -c '.state.devices[0].tcpProtocol'
    @sleep 3
    @echo ">> Triggering CONFIG_UPDATE via dummy-cloud (proxy sends <VT>...<FS><CR>) ..."
    curl -fsS -X POST localhost:{{cloud_port}}/demo/config \
        -H 'content-type: application/json' \
        -d '{"configId":"CFG-FRAMED","key":"mode","value":"safe"}' | jq .
    @sleep 3
    @echo ">> Check dummy-cloud received the CONFIG_ACK (pushed back as a framed message):"
    curl -fsS localhost:{{cloud_port}}/demo/confirms | jq '[.[] | select(.businessId=="CFG-FRAMED")]'

# Persistent TCP session: the proxy keeps a heartbeated socket to the device (no connect-per-message).
# A CONFIG_UPDATE rides the live socket; the device pushes CONFIG_ACK back over the SAME socket.
# Requires the device on the persistent profile: just run-dummy-edge-persistent
demo-config-persistent:
    @echo ">> Applying persistent-session config (proxy dials the device; heartbeats start) ..."
    curl -fsS -X POST localhost:{{cloud_port}}/control/apply-config \
        -H 'content-type: application/json' \
        --data-binary @config/persistent-routes.json | jq -c '.state.devices[0].tcpSession'
    @echo ">> Watch the Switchyard console — edge-gateway-01 turns UP in 'Persistent connections'."
    @sleep 4
    @echo ">> Triggering CONFIG_UPDATE (delivered over the already-open socket) ..."
    curl -fsS -X POST localhost:{{cloud_port}}/demo/config \
        -H 'content-type: application/json' \
        -d '{"configId":"CFG-SESSION","key":"mode","value":"safe"}' | jq .
    @sleep 3
    @echo ">> Cloud received the CONFIG_ACK (pushed back over the same persistent socket):"
    curl -fsS localhost:{{cloud_port}}/demo/confirms | jq '[.[] | select(.businessId=="CFG-SESSION")]'

# Add a CUSTOM message type to the live catalog (Part 3) — no code change, no restart.
# Defines a type outside the starter profile with the xml codec; it shows up in typeDirections immediately.
# (Manage the catalog visually on the Switchyard UI's Catalog tab.) Needs the rebuilt dummy-cloud.
demo-catalog:
    @echo ">> Defining a custom message type DIAGNOSTICS_UPLOAD (xml codec, edge->cloud) ..."
    curl -fsS -X POST localhost:{{cloud_port}}/control/upsert-message-type \
        -H 'content-type: application/json' \
        -d '{"type":"DIAGNOSTICS_UPLOAD","direction":"EDGE_TO_CLOUD","codec":"xml","cloudEndpoint":"/api/diagnostics-upload","businessIdField":"snapshotId"}' \
        | jq '.state.typeDirections'
    @echo ">> DIAGNOSTICS_UPLOAD is now routable — defined at runtime, no profile edit, no restart."

# XML round trip over HTTP: device emits XML, the proxy's xml codec pulls the business id
# from the <commandId> element. Needs dummy-edge on the xml profile (just run-dummy-edge-xml)
# and the rebuilt dummy-cloud (for the upsert-message-type endpoint).
demo-command-http-xml:
    @echo ">> Switching COMMAND_RESULT to the xml codec (live, no restart) ..."
    curl -fsS -X POST localhost:{{cloud_port}}/control/upsert-message-type \
        -H 'content-type: application/json' \
        -d '{"type":"COMMAND_RESULT","direction":"EDGE_TO_CLOUD","codec":"xml","cloudEndpoint":"/api/command-result","businessIdField":"commandId"}' \
        | jq -c '.state.catalogEntries[] | select(.type=="COMMAND_RESULT")'
    @sleep 3
    @echo ">> Firing DEVICE_COMMAND; the device returns an XML COMMAND_RESULT ..."
    curl -fsS -X POST localhost:{{cloud_port}}/demo/command \
        -H 'content-type: application/json' \
        -d '{"commandId":"CMD-XML","action":"REBOOT"}' | jq .
    @sleep 3
    @echo ">> Cloud received it (payload is raw XML; businessId extracted from <commandId>):"
    curl -fsS localhost:{{cloud_port}}/demo/confirms | jq '[.[] | select(.businessId=="CMD-XML")]'

# Push an INVALID routing config (TCP port outside the pool) -> expect rejection
demo-apply-bad-config:
    curl -fsS -X POST localhost:{{cloud_port}}/control/apply-config \
        -H 'content-type: application/json' \
        --data-binary @config/invalid-routes.json | jq .

# Query the control workflow's desired state (via dummy-cloud -> Temporal)
demo-state:
    curl -fsS localhost:{{cloud_port}}/control/state | jq .

# Show the proxy's locally applied state (listeners, routes, enabled flag)
proxy-status:
    curl -fsS localhost:{{proxy_admin_port}}/admin/status | jq .

# Idempotency check: fire the same DEVICE_COMMAND twice -> expect one execution
demo-idempotency:
    curl -fsS -X POST localhost:{{cloud_port}}/demo/command \
        -H 'content-type: application/json' \
        -d '{"commandId":"CMD-DUP","action":"REBOOT"}' | jq -c .
    curl -fsS -X POST localhost:{{cloud_port}}/demo/command \
        -H 'content-type: application/json' \
        -d '{"commandId":"CMD-DUP","action":"REBOOT"}' | jq -c .
    @echo ">> Second call should report duplicate:true — exactly ONE execution in the Temporal UI."
