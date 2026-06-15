"use client";

import { MessageTypesPanel } from "@/components/catalog/message-types-panel";
import { DevicesPanel } from "@/components/routes/devices-panel";
import { usePoll } from "@/hooks/use-poll";
import type { ControlStateResponse } from "@/lib/types";

/**
 * One place to configure routing: the message catalog (what can be routed) on top, the edge
 * devices and their bindings (how each is routed) below — both ride the same control-workflow
 * state, polled once here and shared by both panels.
 */
export default function ConfigPage() {
  const control = usePoll<ControlStateResponse>("/api/control/state", 3000);
  const state = control.data?.state;

  if (!state) {
    return (
      <p className="readout py-10 text-center text-[12px] text-ink-faint">
        {control.error ? `cannot reach Temporal: ${control.error}` : "loading control state…"}
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-10">
      <MessageTypesPanel state={state} onApplied={control.refresh} />
      <DevicesPanel state={state} onApplied={control.refresh} />
    </div>
  );
}
