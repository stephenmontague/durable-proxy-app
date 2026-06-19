import { DUMMY_CLOUD_URL, errorResponse } from "@/lib/temporal";

export const dynamic = "force-dynamic";

// Demo dispatch goes to the cloud app's generic /demo/dispatch, which starts a DeliverToEdge
// workflow for any catalog type. Both apps live on the cloud side of the firewall — the message
// still reaches the edge through Temporal like every real dispatch.
export async function POST(request: Request) {
  try {
    const body = await request.json();
    const messageType = String(body?.messageType ?? "");
    const businessId = String(body?.businessId ?? "");
    if (!messageType || !businessId) {
      return Response.json({ error: "messageType and businessId are required" }, { status: 400 });
    }
    const res = await fetch(`${DUMMY_CLOUD_URL}/demo/dispatch`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ messageType, businessId, payload: body?.payload ?? "" }),
      cache: "no-store",
    });
    const json = await res.json();
    if (!res.ok) {
      return Response.json(
        { error: json?.message ?? json?.error ?? `dummy-cloud returned ${res.status}` },
        { status: 502 },
      );
    }
    return Response.json(json); // { workflowId, duplicate }
  } catch (e) {
    return errorResponse(e);
  }
}
