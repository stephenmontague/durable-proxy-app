# Deploying the proxy to an edge host (Windows / macOS / Linux)

This is a guide for **forking the project and running the proxy on a real on-prem edge machine**, connected to Temporal Cloud and controlled from your own cloud app. It is deliberately **not** a turnkey installer — it describes what you wire up yourself. For what the proxy is, see the [README](../README.md); for the restart mechanics this relies on, see the supervisor contract in [internals.md](internals.md#lifecycle--remote-restart).

## The model in one breath

- The proxy is **one self-contained Spring Boot jar**, **egress-only** — it only ever dials out (to Temporal Cloud and to your cloud app's API).
- It **boots with the `empty` profile**: no message types, no devices, idle. You push the catalog and devices from your cloud/Switchyard UI over the control plane **after** it connects — so there is essentially **no on-site configuration**.
- It needs a **process supervisor** to (a) run it as a background service that starts on boot and (b) honor the remote-restart contract. On Linux that's **systemd**, on macOS **launchd**, on Windows a **Windows service** (WinSW / NSSM). This is the production stand-in for `scripts/proxy-supervisor.sh`.

So "deploy" is really three things: get a JRE on the box, hand it its bootstrap config, and run it under a supervisor. Everything else happens remotely.

## 1. Runtime prerequisite — a JRE

Java **17+ (21 recommended)**. Either require a JRE on the host, or bundle a trimmed one with **`jpackage`** into a native package (`.msi` / `.pkg` / `.deb`) so the operator installs nothing else. This repo ships only the jar:

```sh
mvn -q -pl proxy package -DskipTests   # -> proxy/target/proxy-app-*.jar
```

Producing the native bundle is yours to add — it's out of scope here.

## 2. Bootstrap configuration

The **only** config the host needs; everything operational is pushed from the cloud. Activate the **`cloud`** Spring profile and supply these via environment variables (Spring relaxed binding) or a mounted `application.yml`:

| Env var | Property | What it is |
| --- | --- | --- |
| `--spring.profiles.active=cloud` | — | Targets Temporal Cloud with mTLS (a program arg, not an env var) |
| `TEMPORAL_TARGET` | `spring.temporal.connection.target` | `<ns-id>.<region>.tmprl.cloud:7233` |
| `TEMPORAL_NAMESPACE` | `spring.temporal.namespace` | `<tenant>.<account-id>` — **one namespace per install** |
| `TEMPORAL_KEY_FILE` | `…mtls.key-file` | Path to the client mTLS **private key** |
| `TEMPORAL_CERT_FILE` | `…mtls.cert-chain-file` | Path to the client mTLS **cert chain** |
| `PROXY_CLOUD_BASE_URL` | `proxy.cloud.base-url` | Your cloud app's API base — where edge→cloud POSTs go. **The `cloud` profile does not set this** (default is `localhost:8091`), so you must override it. |
| `SERVER_PORT` | `server.port` | HTTP ingress port (default `8090`) |
| `PROXY_INGRESS_TCP_PORT_POOL` | `proxy.ingress.tcp-port-pool` | Inbound TCP ports IT allocated (default `6000-6010`) |
| `PROXY_INGRESS_FTP_PORT` / `…FTP_ROOT` / `…FTP_USER` / `…FTP_PASSWORD` | `proxy.ingress.ftp-*` | FTP ingress; set `ftp-root` to an **absolute writable path** (a service's working dir is not the repo) |
| `PROXY_SUPERVISED` | — | Set to `true` **by the supervisor** (see §4), not by hand |

> **Namespace-per-install is the isolation model.** Each customer gets their own Temporal namespace + mTLS creds, so a compromised edge box's credentials reach only that customer's own namespace and data. Provisioning the namespace + certs is a **cloud-side** step (out of this repo).

## 3. Network / firewall

Two directions matter, and "egress-only" only describes one of them:

- **Outbound (WAN) — required.** The host must reach **Temporal Cloud** (gRPC, `:7233`) and **your cloud app's API**. That is the *entire* internet-facing surface — **no inbound ports from the internet.** This is the pitch to the customer's IT.
- **Inbound on the LAN — required for device ingress.** Devices on the local network connect **into** the proxy's listeners (HTTP `8090`, the TCP pool, FTP `2221`). So the **host firewall needs LAN inbound rules** for those ports. Egress-only is about the internet boundary; device→proxy traffic is still inbound *on the LAN*. (If the proxy dials the device instead — CLIENT-role TCP / persistent sessions — that leg is outbound on the LAN and needs no inbound rule.)

## 4. Run it under a supervisor

The remote **RESTART / SHUTDOWN** buttons work by the proxy exiting the JVM with a specific code and the supervisor reacting to it (full mechanism in [internals.md](internals.md#lifecycle--remote-restart)):

- exit **`10`** → "relaunch me" (RESTART)
- exit **`0`** → "stay down" (SHUTDOWN)
- the supervisor sets **`PROXY_SUPERVISED=true`** so the proxy knows it will be relaunched (and the UI won't warn before RESTART)

The universal mapping is **"restart on non-zero exit, stay down on clean exit 0"** — which gives you RESTART (exit 10) → relaunch, SHUTDOWN (exit 0) → down, and a crash (non-zero) → relaunch. Each OS expresses it slightly differently. Adapt the examples below; replace paths, the service account, and the env values.

### Linux — systemd

`/etc/systemd/system/durable-proxy.service`:

```ini
[Unit]
Description=Cloud <-> Edge Durable Proxy
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=proxy
WorkingDirectory=/opt/durable-proxy
Environment=PROXY_SUPERVISED=true
Environment=TEMPORAL_TARGET=<ns-id>.<region>.tmprl.cloud:7233
Environment=TEMPORAL_NAMESPACE=<tenant>.<account-id>
Environment=TEMPORAL_KEY_FILE=/etc/durable-proxy/client.key
Environment=TEMPORAL_CERT_FILE=/etc/durable-proxy/client.pem
Environment=PROXY_CLOUD_BASE_URL=https://cloud.example.com
ExecStart=/usr/bin/java -jar /opt/durable-proxy/proxy-app.jar --spring.profiles.active=cloud
Restart=on-failure
RestartSec=2

[Install]
WantedBy=multi-user.target
```

`Restart=on-failure` restarts on any non-zero exit (10 or a crash) and leaves a clean `0` down — exactly the contract. Then `sudo systemctl enable --now durable-proxy`.

### macOS — launchd

`/Library/LaunchDaemons/com.example.durable-proxy.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>com.example.durable-proxy</string>
  <key>ProgramArguments</key>
  <array>
    <string>/usr/bin/java</string>
    <string>-jar</string>
    <string>/opt/durable-proxy/proxy-app.jar</string>
    <string>--spring.profiles.active=cloud</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PROXY_SUPERVISED</key><string>true</string>
    <key>TEMPORAL_TARGET</key><string><ns-id>.<region>.tmprl.cloud:7233</string>
    <key>TEMPORAL_NAMESPACE</key><string><tenant>.<account-id></string>
    <key>TEMPORAL_KEY_FILE</key><string>/etc/durable-proxy/client.key</string>
    <key>TEMPORAL_CERT_FILE</key><string>/etc/durable-proxy/client.pem</string>
    <key>PROXY_CLOUD_BASE_URL</key><string>https://cloud.example.com</string>
  </dict>
  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
  <key>RunAtLoad</key><true/>
  <key>WorkingDirectory</key><string>/opt/durable-proxy</string>
  <key>StandardOutPath</key><string>/var/log/durable-proxy.log</string>
  <key>StandardErrorPath</key><string>/var/log/durable-proxy.err.log</string>
</dict></plist>
```

`KeepAlive → SuccessfulExit=false` relaunches unless the process exited `0` — the same mapping. Then `sudo launchctl load -w …plist`.

### Windows — WinSW (or NSSM)

Native `sc.exe` recovery reacts to *crashes*, not clean exit codes, so it can't tell RESTART (10) from SHUTDOWN (0). Use **WinSW** or **NSSM**, which can.

**WinSW** — `durable-proxy.xml` beside `WinSW.exe`:

```xml
<service>
  <id>durable-proxy</id>
  <name>Cloud-Edge Durable Proxy</name>
  <description>Egress-only Temporal worker bridging cloud and edge.</description>
  <executable>java</executable>
  <arguments>-jar "C:\Program Files\durable-proxy\proxy-app.jar" --spring.profiles.active=cloud</arguments>
  <workingdirectory>C:\ProgramData\durable-proxy</workingdirectory>
  <env name="PROXY_SUPERVISED" value="true"/>
  <env name="TEMPORAL_TARGET" value="<ns-id>.<region>.tmprl.cloud:7233"/>
  <env name="TEMPORAL_NAMESPACE" value="<tenant>.<account-id>"/>
  <env name="TEMPORAL_KEY_FILE" value="C:\ProgramData\durable-proxy\client.key"/>
  <env name="TEMPORAL_CERT_FILE" value="C:\ProgramData\durable-proxy\client.pem"/>
  <env name="PROXY_CLOUD_BASE_URL" value="https://cloud.example.com"/>
  <onfailure action="restart" delay="2 sec"/>
  <log mode="roll-by-size"/>
</service>
```

`<onfailure action="restart"/>` restarts on non-zero exit and leaves a clean `0` down. Then `WinSW.exe install` + `WinSW.exe start`.

**NSSM** equivalent:

```bat
nssm install DurableProxy "C:\path\to\java.exe" "-jar C:\Program Files\durable-proxy\proxy-app.jar --spring.profiles.active=cloud"
nssm set DurableProxy AppEnvironmentExtra PROXY_SUPERVISED=true TEMPORAL_TARGET=... TEMPORAL_NAMESPACE=... TEMPORAL_KEY_FILE=... TEMPORAL_CERT_FILE=... PROXY_CLOUD_BASE_URL=...
nssm set DurableProxy AppExit Default Restart
nssm set DurableProxy AppExit 0 Exit
nssm start DurableProxy
```

`AppExit 0 Exit` + `AppExit Default Restart` is the contract spelled out literally.

> Run the service under a dedicated low-privilege account, start it on boot, and capture stdout/stderr to a rolling log file (the operator has no console).

## 5. First connect, then configure

1. **Provision** the customer's Temporal namespace + mTLS certs (cloud-side).
2. **Install:** put the jar + JRE + certs on the box, drop the config, register the service with `PROXY_SUPERVISED=true`, start it.
3. The proxy **dials Temporal Cloud** on their namespace, registers as the worker on `proxy-main` / `proxy-control`, and `ControlBootstrap` fires one `requestReconcile` — it comes up **empty**.
4. From **your** cloud / Switchyard UI, define the message catalog + devices → pushed as Updates → the proxy **hot-applies** them (opens LAN listeners / dials devices). The customer configures nothing.
5. **Remote restart / shutdown** from the UI now works because the process is supervised.

## 6. Operating it remotely

- **Diagnose without logging in.** Persistent-link drop reasons (`lastError`, recent transitions) ride back to your cloud UI, so you can triage a customer's edge link without RDP/SSH into their box.
- **Logs.** The service captures stdout/stderr; to watch a persistent TCP link breathe, raise `--logging.level.heartbeat=INFO` temporarily.
- **Binary updates.** Redeploy the package and restart the service. (The dev supervisor's copied-jar trick — rebuild in place, then hit RESTART — is a local convenience; in production you deploy a new artifact and restart.)
