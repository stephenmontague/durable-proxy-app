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
