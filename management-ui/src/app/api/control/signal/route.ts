import { DUMMY_CLOUD_URL, controlHandle, errorResponse, temporalClient } from "@/lib/temporal";

export const dynamic = "force-dynamic";

// Config changes route through the cloud app: it signals the proxy-control workflow (the source of
// truth), waits for accept/reject, then persists to its H2 read model on accept — returning
// { accepted, version, message }. Lifecycle commands don't change config, so they signal the
// workflow directly over Temporal. The UI never reaches the proxy's network either way.
const CONFIG_ROUTES: Record<string, (arg: unknown) => { path: string; sendBody: boolean }> = {
  enable: () => ({ path: "/control/enable", sendBody: false }),
  disable: () => ({ path: "/control/disable", sendBody: false }),
  "apply-config": () => ({ path: "/control/apply-config", sendBody: true }),
  "upsert-device": () => ({ path: "/control/upsert-device", sendBody: true }),
  "upsert-message-type": () => ({ path: "/control/upsert-message-type", sendBody: true }),
  "import-catalog": () => ({ path: "/control/import-catalog", sendBody: true }),
  "remove-device": (arg) => ({
    path: `/control/remove-device/${encodeURIComponent(String(arg))}`,
    sendBody: false,
  }),
  "remove-message-type": (arg) => ({
    path: `/control/remove-message-type/${encodeURIComponent(String(arg))}`,
    sendBody: false,
  }),
};

const LIFECYCLE: Record<string, string> = {
  restart: "requestRestart",
  shutdown: "requestShutdown",
};

export async function POST(request: Request) {
  try {
    const body = await request.json();
    const action = String(body?.action ?? "");

    const route = CONFIG_ROUTES[action];
    if (route) {
      const { path, sendBody } = route(body.arg);
      const res = await fetch(`${DUMMY_CLOUD_URL}${path}`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: sendBody ? JSON.stringify(body.arg) : undefined,
        cache: "no-store",
      });
      const json = await res.json();
      if (!res.ok) {
        return Response.json(
          { error: json?.message ?? json?.error ?? `cloud returned ${res.status}` },
          { status: 502 },
        );
      }
      return Response.json(json); // { accepted, version, message? }
    }

    const lifecycle = LIFECYCLE[action];
    if (lifecycle) {
      const client = await temporalClient();
      await controlHandle(client).signal(lifecycle);
      return Response.json({ accepted: true });
    }

    return Response.json({ error: `unknown action '${action}'` }, { status: 400 });
  } catch (e) {
    return errorResponse(e);
  }
}
