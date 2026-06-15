"use client";

import { useState } from "react";
import { toast } from "sonner";
import { TypeForm } from "@/components/catalog/type-form";
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
import { awaitConfigOutcome, postSignal } from "@/lib/actions";
import { DEVICE_FLEET_CATALOG } from "@/lib/starter-catalog";
import type { CatalogEntryDto, ProxyControlState } from "@/lib/types";

/**
 * Message-catalog management: the types this install can route. Lives on the Config page
 * above the device list, so the define-before-bind dependency is visible — a type must exist
 * here before a device below can bind it.
 */
export function MessageTypesPanel({
  state,
  onApplied,
}: {
  state: ProxyControlState;
  onApplied: () => void;
}) {
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<CatalogEntryDto | null>(null);
  const [removing, setRemoving] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const importProfile = async () => {
    setBusy(true);
    try {
      const prevVersion = state.version;
      await postSignal("import-catalog", DEVICE_FLEET_CATALOG);
      const outcome = await awaitConfigOutcome(prevVersion);
      if (outcome.accepted) {
        toast.success("Starter profile imported", {
          description: `${DEVICE_FLEET_CATALOG.length} message types are now editable here.`,
        });
      } else {
        toast.error("Import rejected", { description: outcome.message });
      }
      onApplied();
    } catch (e) {
      toast.error("Import failed", { description: e instanceof Error ? e.message : String(e) });
    } finally {
      setBusy(false);
    }
  };

  const removeType = async (typeName: string) => {
    setBusy(true);
    try {
      const prevVersion = state.version;
      await postSignal("remove-message-type", typeName);
      const outcome = await awaitConfigOutcome(prevVersion);
      if (outcome.accepted) {
        toast.success(`Message type "${typeName}" removed`);
      } else {
        // The workflow rejects a type that's still bound to a device — surface why.
        toast.error("Remove rejected", { description: outcome.message });
      }
      onApplied();
    } catch (e) {
      toast.error("Remove failed", { description: e instanceof Error ? e.message : String(e) });
    } finally {
      setBusy(false);
      setRemoving(null);
    }
  };

  const entries = state.catalogEntries ?? [];
  const profileTypeCount = Object.keys(state.typeDirections ?? {}).length;
  const usesProfileCatalog = entries.length === 0;

  return (
    <section className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="font-mono text-[13px] font-semibold uppercase tracking-[0.14em]">
            Message types
          </h2>
          <p className="mt-1 max-w-2xl text-[12px] text-ink-soft">
            What this install can route — direction, wire codec, and (for inbound) the cloud
            endpoint. A type must exist here before a device below can bind it.
          </p>
        </div>
        <div className="flex shrink-0 gap-2">
          <Button
            variant="secondary"
            className="btn-hard font-mono text-[10px] uppercase tracking-[0.12em]"
            disabled={busy}
            onClick={importProfile}
          >
            Import starter profile
          </Button>
          <Button
            className="btn-hard font-mono text-[10px] uppercase tracking-[0.12em]"
            onClick={() => {
              setEditing(null);
              setFormOpen(true);
            }}
          >
            + Add type
          </Button>
        </div>
      </div>

      {usesProfileCatalog ? (
        <Panel legend="Built-in profile catalog">
          <p className="text-[12px] leading-relaxed text-ink-soft">
            This proxy is routing its <span className="font-semibold">built-in profile catalog</span>
            {profileTypeCount > 0 ? ` (${profileTypeCount} types)` : ""} — defined in code, not yet
            editable here. <span className="font-semibold">Import the starter profile</span> to
            pull those types into editable control state, or <span className="font-semibold">add
            your own</span> to start a fresh catalog for any domain.
          </p>
        </Panel>
      ) : (
        <Panel legend={`catalog · ${entries.length} types`}>
          <table className="w-full border-collapse">
            <thead>
              <tr className="border-b border-ink/60 text-left">
                {["Type", "Direction", "Codec", "Cloud endpoint", "Business id", ""].map((h) => (
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
              {entries.map((entry) => (
                <tr key={entry.type} className="border-b border-hairline/70">
                  <td className="readout py-1.5 pr-2 text-[12px] font-medium">{entry.type}</td>
                  <td className="readout py-1.5 pr-2 text-[10px] text-ink-soft">
                    {entry.direction === "CLOUD_TO_EDGE" ? "cloud ▸ edge" : "edge ▸ cloud"}
                  </td>
                  <td className="py-1.5 pr-2">
                    <span className="chip">{entry.codec}</span>
                  </td>
                  <td className="readout py-1.5 pr-2 text-[11px] text-ink-soft">
                    {entry.cloudEndpoint ?? "—"}
                  </td>
                  <td className="readout py-1.5 pr-2 text-[11px] text-ink-soft">
                    {entry.businessIdField ?? "(content hash)"}
                  </td>
                  <td className="py-1.5">
                    <div className="flex justify-end gap-1.5">
                      <Button
                        size="sm"
                        variant="secondary"
                        className="btn-hard font-mono text-[10px] uppercase tracking-[0.12em]"
                        onClick={() => {
                          setEditing(entry);
                          setFormOpen(true);
                        }}
                      >
                        Edit
                      </Button>
                      <Button
                        size="sm"
                        variant="destructive"
                        className="btn-hard font-mono text-[10px] uppercase tracking-[0.12em]"
                        onClick={() => setRemoving(entry.type)}
                      >
                        Remove
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Panel>
      )}

      <TypeForm
        open={formOpen}
        onOpenChange={setFormOpen}
        state={state}
        editing={editing}
        onApplied={onApplied}
      />

      <Dialog open={removing !== null} onOpenChange={(open) => !open && setRemoving(null)}>
        <DialogContent className="border-ink">
          <DialogHeader>
            <DialogTitle className="font-mono text-sm uppercase tracking-[0.08em]">
              Remove type &quot;{removing}&quot;?
            </DialogTitle>
            <DialogDescription className="text-[13px]">
              The proxy stops routing it on the next reconcile. If any device is still bound to
              this type the control workflow rejects the removal — unbind it from the device
              below first.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="secondary" className="btn-hard" onClick={() => setRemoving(null)}>
              Cancel
            </Button>
            <Button
              variant="destructive"
              className="btn-hard"
              disabled={busy}
              onClick={() => removing && removeType(removing)}
            >
              {busy ? "Removing…" : "Remove"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  );
}
