# dummy-edge

Test-harness **edge target** for the [proxy](../proxy): a fake device exposing all
three transports with one channel per message type, auto-generating the paired confirm.

| Channel (this device)        | Receives          | Pushes back (to the proxy)            |
| ---------------------------- | ----------------- | ------------------------------------- |
| HTTP `POST /commands`      | DEVICE_COMMAND      | COMMAND_RESULT → `POST {proxy}/command-result` |
| TCP port `9001`              | CONFIG_UPDATE | CONFIG_ACK → proxy TCP `6001`    |
| FTP folder `report-requests` (server on `2222`) | REPORT_REQUEST | REPORT_UPLOAD → proxy FTP folder `report-uploads` (`2221`) |

`GET /received` lists everything the device received. Confirms are pushed after a small
simulated delay and retried a few times if the proxy nacks — like a real device.

Run from the repo root: `just run-dummy-edge` (port **8092**). Targets overridable via
`SERVER_PORT`, `EDGE_PROXY_HTTPBASE`, etc.
