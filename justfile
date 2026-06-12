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

# End-to-end HTTP round trip: WAVE_RELEASE (cloud->edge) then PICK_CONFIRM (edge->cloud)
demo-http:
    @echo ">> Triggering WAVE_RELEASE via dummy-cloud ..."
    curl -fsS -X POST localhost:{{cloud_port}}/demo/wave-release \
        -H 'content-type: application/json' \
        -d '{"orderId":"ORD-1001","items":[{"sku":"ABC-123","qty":2}]}' | jq .
    @echo ">> Inspect both standalone activities in the Temporal UI: {{temporal_ui}}"
    @sleep 2
    @echo ">> Check dummy-cloud received the PICK_CONFIRM:"
    curl -fsS localhost:{{cloud_port}}/demo/confirms | jq .

# TCP round trip: CONTAINER_PUTAWAY (cloud->edge, device port 9001) then
# PUTAWAY_CONFIRM (edge->cloud, proxy port 6001)
demo-putaway-tcp:
    @echo ">> Triggering CONTAINER_PUTAWAY via dummy-cloud ..."
    curl -fsS -X POST localhost:{{cloud_port}}/demo/putaway \
        -H 'content-type: application/json' \
        -d '{"containerId":"CTN-2001","location":"A-01-03"}' | jq .
    @sleep 2
    @echo ">> Check dummy-cloud received the PUTAWAY_CONFIRM:"
    curl -fsS localhost:{{cloud_port}}/demo/confirms | jq .

# FTP round trip: CYCLE_COUNT_REQ (cloud->edge, device folder cycle-count) then
# CYCLE_COUNT_CONFIRM (edge->cloud, proxy folder cycle-count-confirm)
demo-cycle-count-ftp:
    @echo ">> Triggering CYCLE_COUNT_REQ via dummy-cloud ..."
    curl -fsS -X POST localhost:{{cloud_port}}/demo/cycle-count \
        -H 'content-type: application/json' \
        -d '{"countId":"CC-3001","location":"B-02-07"}' | jq .
    @sleep 3
    @echo ">> Check dummy-cloud received the CYCLE_COUNT_CONFIRM:"
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
demo-putaway-tcp-framed:
    @echo ">> Applying MLLP wire-protocol config (hot, no restart) ..."
    curl -fsS -X POST localhost:{{cloud_port}}/control/apply-config \
        -H 'content-type: application/json' \
        --data-binary @config/framed-routes.json | jq -c '.state.devices[0].tcpProtocol'
    @sleep 3
    @echo ">> Triggering CONTAINER_PUTAWAY via dummy-cloud (proxy sends <VT>...<FS><CR>) ..."
    curl -fsS -X POST localhost:{{cloud_port}}/demo/putaway \
        -H 'content-type: application/json' \
        -d '{"containerId":"CTN-FRAMED","location":"A-01-03"}' | jq .
    @sleep 3
    @echo ">> Check dummy-cloud received the PUTAWAY_CONFIRM (pushed back as a framed message):"
    curl -fsS localhost:{{cloud_port}}/demo/confirms | jq '[.[] | select(.businessId=="CTN-FRAMED")]'

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

# Idempotency check: fire the same WAVE_RELEASE twice -> expect one execution
demo-idempotency:
    curl -fsS -X POST localhost:{{cloud_port}}/demo/wave-release \
        -H 'content-type: application/json' \
        -d '{"orderId":"ORD-DUP","items":[{"sku":"X","qty":1}]}' | jq -c .
    curl -fsS -X POST localhost:{{cloud_port}}/demo/wave-release \
        -H 'content-type: application/json' \
        -d '{"orderId":"ORD-DUP","items":[{"sku":"X","qty":1}]}' | jq -c .
    @echo ">> Second call should report duplicate:true — exactly ONE execution in the Temporal UI."
