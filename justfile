# Cloud <-> Edge Proxy — build & demo recipes.
# Local dev targets the Docker Temporal at localhost:7233 (Server 1.31+ with
# activity.enableStandalone=true, Web UI at http://localhost:8080).
# `just temporal-dev` is the no-Docker fallback. Requires: just, Java 17+, Maven, Temporal CLI.

set shell := ["bash", "-cu"]

cloud_port  := "8091"
temporal_ui := "http://localhost:8080"

# Show available recipes
default:
    @just --list

# ---------------------------------------------------------------------------
# One-command demo stack (backgrounds everything to logs/; namespace defaults to demo)
# ---------------------------------------------------------------------------

# Start the whole stack on one namespace: Temporal (if needed) + proxy + cloud + edge + UI
up ns="demo":
    #!/usr/bin/env bash
    set -uo pipefail
    cd "{{justfile_directory()}}"
    mkdir -p logs
    for p in 8090 8091 8092 3000; do
      if lsof -ti:$p >/dev/null 2>&1; then
        echo "!! port $p already in use — run 'just down' first"; exit 1
      fi
    done
    # Temporal: reuse :7233 if reachable, else start the dev server (standalone activities on).
    if temporal operator namespace list >/dev/null 2>&1; then
      echo ">> Temporal already up on :7233"
    else
      echo ">> starting Temporal dev server -> logs/temporal.log"
      nohup temporal server start-dev --dynamic-config-value activity.enableStandalone=true \
        > logs/temporal.log 2>&1 &
      echo $! > logs/temporal.pid
      disown
      for i in $(seq 1 30); do temporal operator namespace list >/dev/null 2>&1 && break; sleep 1; done
    fi
    # Ensure the namespace exists (idempotent) and point the UI + cloud at it.
    temporal operator namespace create --namespace {{ns}} --retention 24h >/dev/null 2>&1 || true
    printf 'TEMPORAL_NAMESPACE=%s\nDUMMY_CLOUD_URL=http://localhost:8091\n' {{ns}} > management-ui/.env.local
    echo ">> building (proxy + cloud + edge) ..."
    mvn -q -DskipTests package
    [ -d management-ui/node_modules ] || (cd management-ui && npm install >/dev/null 2>&1)
    echo ">> starting proxy (managed, {{ns}}) -> logs/proxy.log"
    PROXY_SUPERVISED=true nohup ./scripts/proxy-supervisor.sh --spring.temporal.namespace={{ns}} > logs/proxy.log 2>&1 &
    disown
    echo ">> starting cloud ({{ns}}, :8091) -> logs/cloud.log"
    nohup mvn -q -pl dummy-cloud spring-boot:run -Dspring-boot.run.profiles=local \
      -Dspring-boot.run.arguments="--server.port=8091 --cloud.temporal.namespace={{ns}} --spring.datasource.url=jdbc:h2:file:./data/cloud-{{ns}}" \
      > logs/cloud.log 2>&1 &
    disown
    echo ">> starting edge (:8092) -> logs/edge.log"
    nohup mvn -q -pl dummy-edge spring-boot:run -Dspring-boot.run.profiles=local > logs/edge.log 2>&1 &
    disown
    echo ">> starting UI (:3000) -> logs/ui.log"
    (cd management-ui && nohup npm run dev > ../logs/ui.log 2>&1 &)
    echo ">> waiting for cloud + UI to answer ..."
    for i in $(seq 1 45); do
      curl -fsS -o /dev/null localhost:8091/control/state 2>/dev/null && \
        curl -fsS -o /dev/null localhost:3000 2>/dev/null && break
      sleep 2
    done
    echo ""
    echo ">> stack up on namespace '{{ns}}':"
    echo ">>   UI          http://localhost:3000"
    echo ">>   Cloud API   http://localhost:8091"
    echo ">>   Temporal UI http://localhost:8080  (dev-server fallback: http://localhost:8233)"
    echo ">> tail a log: just logs <temporal|proxy|cloud|edge|ui>   ·   stop all: just down"

# Stop everything started by 'just up'
down:
    #!/usr/bin/env bash
    set -uo pipefail
    cd "{{justfile_directory()}}"
    echo ">> stopping proxy / cloud / edge / UI ..."
    for p in 8090 8091 8092 3000; do
      pids=$(lsof -ti:$p 2>/dev/null || true)
      [ -n "$pids" ] && kill $pids 2>/dev/null || true
    done
    pkill -f proxy-supervisor.sh 2>/dev/null || true
    pkill -f 'spring-boot:run' 2>/dev/null || true
    if [ -f logs/temporal.pid ]; then
      echo ">> stopping the Temporal dev server we started ..."
      kill "$(cat logs/temporal.pid)" 2>/dev/null || true
      pkill -f 'temporal server start-dev' 2>/dev/null || true
      rm -f logs/temporal.pid
    fi
    echo ">> done."

# Tail a service log, e.g. just logs proxy
logs name:
    tail -F logs/{{name}}.log

# Restart ONE service (rebuilds from source, picks up code changes), leaving the rest up.
# e.g. just restart edge   ·   proxy also restarts via the UI's Restart button.
restart name ns="demo":
    #!/usr/bin/env bash
    set -uo pipefail
    cd "{{justfile_directory()}}"
    mkdir -p logs
    kill_port() { local pids; pids=$(lsof -ti:"$1" 2>/dev/null || true); [ -n "$pids" ] && kill $pids 2>/dev/null || true; }
    case "{{name}}" in
      proxy)
        kill_port 8090; pkill -f proxy-supervisor.sh 2>/dev/null || true; sleep 1
        echo ">> rebuilding + restarting proxy on {{ns}} ..."
        mvn -q -pl proxy package -DskipTests
        PROXY_SUPERVISED=true nohup ./scripts/proxy-supervisor.sh --spring.temporal.namespace={{ns}} > logs/proxy.log 2>&1 &
        disown ;;
      cloud)
        kill_port 8091; sleep 1
        echo ">> restarting cloud on {{ns}} ..."
        nohup mvn -q -pl dummy-cloud spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments="--server.port=8091 --cloud.temporal.namespace={{ns}} --spring.datasource.url=jdbc:h2:file:./data/cloud-{{ns}}" > logs/cloud.log 2>&1 &
        disown ;;
      edge)
        kill_port 8092; sleep 1
        echo ">> restarting edge ..."
        nohup mvn -q -pl dummy-edge spring-boot:run -Dspring-boot.run.profiles=local > logs/edge.log 2>&1 &
        disown ;;
      ui)
        kill_port 3000; sleep 1
        echo ">> restarting UI ..."
        ( cd management-ui && nohup npm run dev > ../logs/ui.log 2>&1 & ) ;;
      *)
        echo "usage: just restart <proxy|cloud|edge|ui> [ns]"; exit 1 ;;
    esac
    echo ">> {{name}} restarting — tail with: just logs {{name}}"

# ---------------------------------------------------------------------------
# Build & test
# ---------------------------------------------------------------------------

# Build everything (proxy + dummy cloud/edge)
build:
    mvn -q clean package

# Run the Java unit tests
test:
    mvn -q test

# Run the management UI unit tests (WireString/validator parity with Java)
test-ui:
    @[ -d management-ui/node_modules ] || (cd management-ui && npm install)
    cd management-ui && npm test

# Production build of the management UI (needed before run-ui-ns)
build-ui:
    @[ -d management-ui/node_modules ] || (cd management-ui && npm install)
    cd management-ui && npm run build

# ---------------------------------------------------------------------------
# Run the stack (each in its own terminal; Temporal must be up first)
# ---------------------------------------------------------------------------

# Start a local Temporal dev server with standalone activities (no Docker; UI at :8233)
temporal-dev:
    temporal server start-dev \
        --dynamic-config-value activity.enableStandalone=true

# Run the proxy (Spring profile: local)
run-proxy:
    mvn -q -pl proxy spring-boot:run -Dspring-boot.run.profiles=local

# Run the proxy under a restart-on-exit supervisor (needed for the UI's Restart button); optional namespace
run-proxy-managed ns="default":
    mvn -q -pl proxy package -DskipTests
    ./scripts/proxy-supervisor.sh --spring.temporal.namespace={{ns}}

# Run the dummy cloud app
run-dummy-cloud:
    mvn -q -pl dummy-cloud spring-boot:run -Dspring-boot.run.profiles=local

# Run the dummy edge device
run-dummy-edge:
    mvn -q -pl dummy-edge spring-boot:run -Dspring-boot.run.profiles=local

# Run the management UI (http://localhost:3000)
run-ui:
    @[ -d management-ui/node_modules ] || (cd management-ui && npm install)
    cd management-ui && npm run dev

# ---------------------------------------------------------------------------
# Demos (proxy + cloud + edge + UI up; drive from another terminal)
# ---------------------------------------------------------------------------

# HTTP round trip: DEVICE_COMMAND (cloud->edge) then COMMAND_RESULT (edge->cloud)
demo-command:
    @echo ">> Triggering DEVICE_COMMAND via dummy-cloud ..."
    curl -fsS -X POST localhost:{{cloud_port}}/demo/command \
        -H 'content-type: application/json' \
        -d '{"commandId":"CMD-1001","action":"REBOOT"}' | jq .
    @echo ">> Inspect both standalone activities in the Temporal UI: {{temporal_ui}}"
    @sleep 2
    @echo ">> Check dummy-cloud received the COMMAND_RESULT:"
    curl -fsS localhost:{{cloud_port}}/demo/confirms | jq .

# TCP round trip: CONFIG_UPDATE (cloud->edge) then CONFIG_ACK (edge->cloud)
demo-config-tcp:
    @echo ">> Triggering CONFIG_UPDATE via dummy-cloud ..."
    curl -fsS -X POST localhost:{{cloud_port}}/demo/config \
        -H 'content-type: application/json' \
        -d '{"configId":"CFG-2001","key":"reportingIntervalSec","value":30}' | jq .
    @sleep 2
    @echo ">> Check dummy-cloud received the CONFIG_ACK:"
    curl -fsS localhost:{{cloud_port}}/demo/confirms | jq .

# FTP round trip: REPORT_REQUEST (cloud->edge) then REPORT_UPLOAD (edge->cloud)
demo-report-ftp:
    @echo ">> Triggering REPORT_REQUEST via dummy-cloud ..."
    curl -fsS -X POST localhost:{{cloud_port}}/demo/report \
        -H 'content-type: application/json' \
        -d '{"reportId":"RPT-3001","kind":"daily-metrics"}' | jq .
    @sleep 3
    @echo ">> Check dummy-cloud received the REPORT_UPLOAD:"
    curl -fsS localhost:{{cloud_port}}/demo/confirms | jq .

# Hot-apply a routing config (no restart)
demo-apply-config file="config/sample-routes.json":
    curl -fsS -X POST localhost:{{cloud_port}}/control/apply-config \
        -H 'content-type: application/json' \
        --data-binary @{{file}} | jq .

# Define a new message type at runtime — no code, no restart
demo-catalog:
    @echo ">> Defining a custom message type DIAGNOSTICS_UPLOAD (xml codec, edge->cloud) ..."
    curl -fsS -X POST localhost:{{cloud_port}}/control/upsert-message-type \
        -H 'content-type: application/json' \
        -d '{"type":"DIAGNOSTICS_UPLOAD","direction":"EDGE_TO_CLOUD","codec":"xml","cloudEndpoint":"/api/diagnostics-upload","businessIdField":"snapshotId"}' \
        | jq '.state.typeDirections'
    @echo ">> DIAGNOSTICS_UPLOAD is now routable — defined at runtime, no profile edit, no restart."

# Remotely enable this install
demo-enable:
    curl -fsS -X POST localhost:{{cloud_port}}/control/enable | jq .

# Remotely disable this install (soft off — listeners stop, control stays up)
demo-disable:
    curl -fsS -X POST localhost:{{cloud_port}}/control/disable | jq .

# ---------------------------------------------------------------------------
# Persistent-session demo: the proxy keeps a heartbeated socket open to the device
# ---------------------------------------------------------------------------

# Run the edge as a persistent-session device (proxy dials in on 9100 + heartbeats)
run-dummy-edge-persistent:
    mvn -q -pl dummy-edge spring-boot:run -Dspring-boot.run.profiles=local,persistent

# Deliver over the live persistent socket (pair with run-dummy-edge-persistent)
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

# ---------------------------------------------------------------------------
# Multi-sandbox demo: the SAME binaries become two clients (warehouse + smart-grid)
# by config alone. A = sandbox-a (proxy 8090 cloud 8091 UI 3000); B = sandbox-b (8190/8191/3001).
# Full runbook: docs/multi-sandbox-demo.md
# ---------------------------------------------------------------------------

# Create the sandbox-a / sandbox-b Temporal namespaces (safe to re-run)
sandbox-namespaces:
    -temporal operator namespace create --namespace sandbox-a --retention 24h 2>/dev/null || true
    -temporal operator namespace create --namespace sandbox-b --retention 24h 2>/dev/null || true
    @temporal operator namespace list | grep -E "Name:.*sandbox-[ab]" || echo "(check: namespaces sandbox-a / sandbox-b)"

# Run a proxy on a namespace + port, e.g. just run-proxy-ns sandbox-a 8090
run-proxy-ns ns port:
    mvn -q -pl proxy spring-boot:run -Dspring-boot.run.profiles=local \
        -Dspring-boot.run.arguments="--server.port={{port}} --spring.temporal.namespace={{ns}} --logging.level.heartbeat=INFO"

# Run a dummy cloud on a namespace + port, e.g. just run-cloud-ns sandbox-a 8091
run-cloud-ns ns port:
    mvn -q -pl dummy-cloud spring-boot:run -Dspring-boot.run.profiles=local \
        -Dspring-boot.run.arguments="--server.port={{port}} --cloud.temporal.namespace={{ns}} --spring.datasource.url=jdbc:h2:file:./data/cloud-{{ns}}"

# Run the warehouse (sandbox-a) edge — CSV telemetry, STX/ETX framing
run-dummy-edge-sandbox-a:
    mvn -q -pl dummy-edge spring-boot:run -Dspring-boot.run.profiles=local,sandbox-a

# Run the smart-grid (sandbox-b) edge — XML telemetry, custom framing
run-dummy-edge-sandbox-b:
    mvn -q -pl dummy-edge spring-boot:run -Dspring-boot.run.profiles=local,sandbox-b

# Run a UI bound to a sandbox (run build-ui first), e.g. just run-ui-ns sandbox-a 3000 8091
run-ui-ns ns port cloud_port:
    cd management-ui && printf 'TEMPORAL_NAMESPACE=%s\nDUMMY_CLOUD_URL=http://localhost:%s\n' {{ns}} {{cloud_port}} > .env.local && exec npx next start -p {{port}}

# Import a sandbox's catalog + apply its devices, e.g. just sandbox-apply sandbox-a 8091
sandbox-apply name cloud_port:
    -curl -fsS -X POST localhost:{{cloud_port}}/control/remove-device/edge-gateway-01 > /dev/null 2>&1 || true
    curl -fsS -X POST localhost:{{cloud_port}}/control/import-catalog \
        -H 'content-type: application/json' --data-binary @config/{{name}}-catalog.json | jq -c '.state.typeDirections'
    curl -fsS -X POST localhost:{{cloud_port}}/control/apply-config \
        -H 'content-type: application/json' --data-binary @config/{{name}}-routes.json | jq -c '[.state.devices[].deviceId]'
