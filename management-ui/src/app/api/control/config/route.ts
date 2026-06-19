import { DUMMY_CLOUD_URL, errorResponse } from "@/lib/temporal";

export const dynamic = "force-dynamic";

// Desired config for the Config + Dispatch tabs — served from the cloud app's H2 read model, so the
// UI hydrates without a Temporal Query (a billable Action) on every poll. Live proxy status (applied
// state, liveness) is the Dashboard's concern and stays on /api/control/state (Temporal).
export async function GET() {
  try {
    const res = await fetch(`${DUMMY_CLOUD_URL}/control/state`, { cache: "no-store" });
    const json = await res.json();
    if (!res.ok) {
      return Response.json(
        { error: `cloud /control/state returned ${res.status}` },
        { status: 502 },
      );
    }
    // Shape it as ControlStateResponse; liveness is omitted (the Dashboard owns it).
    return Response.json({ state: json.state, liveness: null });
  } catch (e) {
    return errorResponse(e);
  }
}
