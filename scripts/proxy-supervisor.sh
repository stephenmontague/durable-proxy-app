#!/usr/bin/env bash
# Restart-on-exit supervisor for the proxy. The cloud's "restart" lifecycle command makes
# the proxy exit with code 10 (ProxyControlPoller.RESTART_EXIT_CODE) — we relaunch it.
# Any other exit code (including 0 from a "shutdown" command) stays down.
# In production this role is played by systemd / a Windows service with Restart=on-failure.
set -u
cd "$(dirname "$0")/.."

JAR=$(ls proxy/target/proxy-app-*.jar 2>/dev/null | grep -v '\-run\.jar' | head -1)
if [ -z "${JAR}" ]; then
  echo ">> no proxy jar found — run 'mvn -q -pl proxy package -DskipTests' first" >&2
  exit 1
fi

# Run from a copy: Spring Boot lazy-loads classes from the jar, so rebuilding it in
# place under a running proxy causes ClassNotFoundException. The copy also makes
# "rebuild, then RESTART from the management UI" behave like a real deploy.
RUN_JAR=proxy/target/proxy-app-run.jar

while true; do
  cp "${JAR}" "${RUN_JAR}"
  echo ">> supervisor: launching ${RUN_JAR} (from $(basename "${JAR}"))"
  java -jar "${RUN_JAR}" --spring.profiles.active=local "$@"
  code=$?
  if [ "${code}" -eq 10 ]; then
    echo ">> supervisor: proxy requested RESTART (exit 10) — relaunching in 1s"
    sleep 1
    continue
  fi
  echo ">> supervisor: proxy exited with code ${code} — staying down"
  exit "${code}"
done
