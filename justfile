# Cloud <-> Edge durable proxy — build & run recipes.
# Local dev targets the Docker Temporal at localhost:7233 (Server 1.31+ with
# activity.enableStandalone=true, Web UI at http://localhost:8080).
# `just temporal-dev` is the no-Docker fallback. Requires: just, Java 21+, Maven, Temporal CLI.
#
# The reference cloud + edge apps and the Switchyard UI (plus a one-command `just up` demo
# stack) live in a separate repo: https://github.com/stephenmontague/durable-proxy-app-demo

set shell := ["bash", "-cu"]

# Show available recipes
default:
    @just --list

# Build the proxy jar
build:
    mvn -q clean package

# Run the Java unit tests (routing core, validators, codecs, TCP wire protocol, catalog signals)
test:
    mvn -q test

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
