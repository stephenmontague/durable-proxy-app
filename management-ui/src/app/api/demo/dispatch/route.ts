import { errorResponse } from "@/lib/temporal";

export const dynamic = "force-dynamic";

// Demo dispatch goes to dummy-cloud's REST API. Both apps live on the cloud side of the
// firewall, so this is not a proxy-network call — the message still reaches the edge
// through Temporal like every real dispatch.
const DUMMY_CLOUD_URL = process.env.DUMMY_CLOUD_URL ?? "http://localhost:8091";

const KINDS = new Set(["command", "config", "report"]);

export async function POST(request: Request) {
  try {
    const body = await request.json();
    const kind = String(body?.kind ?? "");
    if (!KINDS.has(kind)) {
      return Response.json({ error: `unknown dispatch kind '${kind}'` }, { status: 400 });
    }
    const res = await fetch(`${DUMMY_CLOUD_URL}/demo/${kind}`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body?.payload ?? {}),
      cache: "no-store",
    });
    const json = await res.json();
    if (!res.ok) {
      return Response.json({ error: json?.message ?? `dummy-cloud returned ${res.status}` }, { status: 502 });
    }
    return Response.json(json);
  } catch (e) {
    return errorResponse(e);
  }
}
