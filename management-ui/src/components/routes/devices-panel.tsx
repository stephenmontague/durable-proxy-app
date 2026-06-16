"use client";

import { useState } from "react";
import { toast } from "sonner";
import { DeviceWizard } from "@/components/routes/device-wizard";
import { JsonEditorDialog } from "@/components/routes/json-editor-dialog";
import { Panel } from "@/components/ui-custom/panel";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { postSignal } from "@/lib/actions";
import type { EdgeConfig, ProxyControlState } from "@/lib/types";

/**
 * Edge devices and their routing bindings. Lives on the Config page below the message-types
 * panel — each binding ties a type (from that panel) to a transport + channel on this device.
 */
export function DevicesPanel({
  state,
  onApplied,
}: {
  state: ProxyControlState;
  onApplied: () => void;
}) {
  const [wizardOpen, setWizardOpen] = useState(false);
  const [editing, setEditing] = useState<EdgeConfig | null>(null);
  const [jsonOpen, setJsonOpen] = useState(false);
  const [removing, setRemoving] = useState<string | null>(null);
  const [removeBusy, setRemoveBusy] = useState(false);

  const removeDevice = async (deviceId: string) => {
    setRemoveBusy(true);
    try {
      const outcome = await postSignal("remove-device", deviceId);
      if (outcome.accepted) {
        toast.success(`Device "${deviceId}" removed`);
      } else {
        toast.error("Remove rejected", { description: outcome.message });
      }
      onApplied();
    } catch (e) {
      toast.error("Remove failed", { description: e instanceof Error ? e.message : String(e) });
    } finally {
      setRemoveBusy(false);
      setRemoving(null);
    }
  };

  return (
    <section className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="font-mono text-[13px] font-semibold uppercase tracking-[0.14em]">
            Devices
          </h2>
          <p className="mt-1 text-[12px] text-ink-soft">
            Desired state v{state.version} · each binding routes a message type over a transport
            and channel; changes go live with no restart.
          </p>
        </div>
        <div className="flex gap-2">
          <Button
            variant="secondary"
            className="btn-hard font-mono text-[10px] uppercase tracking-[0.12em]"
            onClick={() => setJsonOpen(true)}
          >
            Raw JSON
          </Button>
          <Button
            className="btn-hard font-mono text-[10px] uppercase tracking-[0.12em]"
            onClick={() => {
              setEditing(null);
              setWizardOpen(true);
            }}
          >
            + Add device
          </Button>
        </div>
      </div>

      {state.devices.length === 0 && (
        <Panel legend="No devices">
          <p className="readout py-4 text-center text-[12px] text-ink-faint">
            no edge devices configured — add one from a template
          </p>
        </Panel>
      )}

      {state.devices.map((device) => (
        <Panel key={device.deviceId} legend={`device · ${device.deviceId}`}>
          <div className="mb-3 flex flex-wrap items-center gap-2">
            {device.baseUrl && <span className="chip">http {device.baseUrl}</span>}
            {device.host && <span className="chip">host {device.host}</span>}
            {device.ftpPort && <span className="chip">ftp :{device.ftpPort}</span>}
            <div className="flex-1" />
            <Button
              size="sm"
              variant="secondary"
              className="btn-hard font-mono text-[10px] uppercase tracking-[0.12em]"
              onClick={() => {
                setEditing(device);
                setWizardOpen(true);
              }}
            >
              Edit
            </Button>
            <Button
              size="sm"
              variant="destructive"
              className="btn-hard font-mono text-[10px] uppercase tracking-[0.12em]"
              onClick={() => setRemoving(device.deviceId)}
            >
              Remove
            </Button>
          </div>
          <table className="w-full border-collapse">
            <thead>
              <tr className="border-b border-ink/60 text-left">
                {["Message type", "Direction", "Via", "Channel"].map((h) => (
                  <th
                    key={h}
                    className="readout pb-1 pr-2 text-[9px] font-semibold uppercase tracking-[0.14em] text-ink-faint"
                  >
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {device.bindings.map((b, i) => {
                const direction = b.messageType ? state.typeDirections[b.messageType] : undefined;
                return (
                  <tr key={i} className="border-b border-hairline/70">
                    <td className="readout py-1.5 pr-2 text-[12px] font-medium">
                      {b.messageType ?? "(multi-type resolver)"}
                    </td>
                    <td className="readout py-1.5 pr-2 text-[10px] text-ink-soft">
                      {direction === "CLOUD_TO_EDGE"
                        ? "cloud ▸ edge"
                        : direction === "EDGE_TO_CLOUD"
                          ? "edge ▸ cloud"
                          : "—"}
                    </td>
                    <td className="py-1.5 pr-2">
                      <span className="chip">{b.transport}</span>
                    </td>
                    <td className="readout py-1.5 text-[12px]">{b.channel.value}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </Panel>
      ))}

      <DeviceWizard
        open={wizardOpen}
        onOpenChange={setWizardOpen}
        state={state}
        editing={editing}
        onApplied={onApplied}
      />
      <JsonEditorDialog
        open={jsonOpen}
        onOpenChange={setJsonOpen}
        state={state}
        onApplied={onApplied}
      />

      <Dialog open={removing !== null} onOpenChange={(open) => !open && setRemoving(null)}>
        <DialogContent className="border-ink">
          <DialogHeader>
            <DialogTitle className="font-mono text-sm uppercase tracking-[0.08em]">
              Remove device &quot;{removing}&quot;?
            </DialogTitle>
            <DialogDescription className="text-[13px]">
              Its listeners close and outbound routes disappear on the proxy&apos;s next
              reconcile. The device itself is untouched.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="secondary" className="btn-hard" onClick={() => setRemoving(null)}>
              Cancel
            </Button>
            <Button
              variant="destructive"
              className="btn-hard"
              disabled={removeBusy}
              onClick={() => removing && removeDevice(removing)}
            >
              {removeBusy ? "Removing…" : "Remove"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  );
}
