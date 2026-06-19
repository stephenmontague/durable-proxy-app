"use client";

// Add / edit one message type. Mirrors the proxy's CatalogValidator so mistakes surface inline
// before the upsertMessageType signal goes out; the control workflow still validates on receipt.

import { useEffect, useMemo, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { FlowLegend } from "@/components/catalog/flow-legend";
import { postSignal } from "@/lib/actions";
import { CODECS, type CatalogEntryDto, type CodecName, type Direction, type ProxyControlState } from "@/lib/types";
import { validateCatalogEntry } from "@/lib/validate";

const EMPTY: CatalogEntryDto = {
  type: "",
  direction: "CLOUD_TO_EDGE",
  codec: "json",
  cloudEndpoint: null,
  businessIdField: null,
};

const CODEC_HINT: Record<CodecName, string> = {
  json: "Payload is JSON; the business-id field is read from it.",
  xml: "Payload is XML; the business-id is read from that element.",
  raw: "Opaque passthrough — no parsing, dedup by content hash.",
};

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label className="text-[10px] font-semibold uppercase tracking-[0.14em] text-ink-soft">
        {label}
      </Label>
      {children}
      {hint && <span className="text-[10px] leading-snug text-ink-faint">{hint}</span>}
    </div>
  );
}

export function TypeForm({
  open,
  onOpenChange,
  state,
  editing,
  onApplied,
  onCreated,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  state: ProxyControlState;
  editing: CatalogEntryDto | null;
  onApplied: () => void;
  /** Called with the type name after a brand-new type is added (not on edit). */
  onCreated?: (type: string) => void;
}) {
  const isEdit = editing != null;
  const [draft, setDraft] = useState<CatalogEntryDto>(EMPTY);
  const [applying, setApplying] = useState(false);

  useEffect(() => {
    if (!open) return;
    setApplying(false);
    setDraft(editing ? { ...editing } : EMPTY);
  }, [open, editing]);

  const edgeToCloud = draft.direction === "EDGE_TO_CLOUD";

  const errors = useMemo(() => {
    const errs = validateCatalogEntry(draft);
    // Adding a name that already exists would be an unintended replace — flag it.
    if (!isEdit && draft.type.trim() !== "") {
      const exists = (state.catalogEntries ?? []).some((e) => e.type === draft.type.trim());
      if (exists) errs.push(`duplicate message type: ${draft.type.trim()}`);
    }
    return errs;
  }, [draft, isEdit, state.catalogEntries]);

  const apply = async () => {
    setApplying(true);
    try {
      const entry: CatalogEntryDto = {
        type: draft.type.trim(),
        direction: draft.direction,
        codec: draft.codec,
        cloudEndpoint: edgeToCloud && draft.cloudEndpoint?.trim() ? draft.cloudEndpoint.trim() : null,
        businessIdField: draft.businessIdField?.trim() ? draft.businessIdField.trim() : null,
        // Only meaningful for inbound dedup; force off for CLOUD_TO_EDGE so it can't silently linger.
        allowDuplicates: edgeToCloud ? (draft.allowDuplicates ?? false) : false,
      };
      const outcome = await postSignal("upsert-message-type", entry);
      if (outcome.accepted) {
        toast.success(`Message type "${entry.type}" saved`, {
          description: "The proxy rebuilds its catalog on the next reconcile.",
        });
        onApplied();
        if (!isEdit) onCreated?.(entry.type);
        onOpenChange(false);
      } else {
        toast.error("Rejected by the control workflow", { description: outcome.message });
      }
    } catch (e) {
      toast.error("Save failed", { description: e instanceof Error ? e.message : String(e) });
    } finally {
      setApplying(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="border-ink sm:max-w-lg">
        <DialogHeader>
          <DialogTitle className="font-mono text-sm uppercase tracking-[0.1em]">
            {isEdit ? `Edit type · ${editing?.type}` : "Add message type"}
          </DialogTitle>
          <DialogDescription className="text-[12px]">
            Defines a flow the proxy can route. Cloud → Edge is dispatched to the device;
            Edge → Cloud is POSTed to a cloud endpoint.
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          <FlowLegend direction={draft.direction} />

          <Field label="Type name" hint="A stable key, conventionally UPPER_SNAKE_CASE.">
            <Input
              value={draft.type}
              disabled={isEdit}
              placeholder="SHIPMENT_NOTICE"
              onChange={(e) => setDraft({ ...draft, type: e.target.value })}
            />
          </Field>

          <div className="grid grid-cols-2 gap-4">
            <Field
              label="Direction"
              hint="Cloud → Edge: the proxy dispatches to the device. Edge → Cloud: the device sends to the proxy, which forwards to the cloud endpoint."
            >
              <Select
                value={draft.direction}
                onValueChange={(v) =>
                  setDraft({
                    ...draft,
                    direction: v as Direction,
                    // endpoint only applies to Edge → Cloud; drop it when switching away
                    cloudEndpoint: v === "EDGE_TO_CLOUD" ? draft.cloudEndpoint : null,
                  })
                }
              >
                <SelectTrigger size="sm" className="readout text-[12px]">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="CLOUD_TO_EDGE" className="readout text-[12px]">
                    Cloud → Edge
                  </SelectItem>
                  <SelectItem value="EDGE_TO_CLOUD" className="readout text-[12px]">
                    Edge → Cloud
                  </SelectItem>
                </SelectContent>
              </Select>
            </Field>

            <Field label="Codec" hint={CODEC_HINT[draft.codec]}>
              <Select
                value={draft.codec}
                onValueChange={(v) => setDraft({ ...draft, codec: v as CodecName })}
              >
                <SelectTrigger size="sm" className="readout text-[12px]">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {CODECS.map((c) => (
                    <SelectItem key={c} value={c} className="readout text-[12px]">
                      {c}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
          </div>

          {edgeToCloud && (
            <Field
              label="Cloud endpoint"
              hint="Path on the cloud app the proxy forwards this inbound message to — e.g. /api/command-result. Lives on the cloud; separate from the device's ingress channel."
            >
              <Input
                value={draft.cloudEndpoint ?? ""}
                placeholder="/api/shipment-notice"
                onChange={(e) => setDraft({ ...draft, cloudEndpoint: e.target.value })}
              />
            </Field>
          )}

          <Field
            label="Business id field"
            hint="Payload field/element used as the dedup key; blank falls back to a content hash."
          >
            <Input
              value={draft.businessIdField ?? ""}
              placeholder={draft.codec === "raw" ? "(ignored for raw)" : "commandId"}
              disabled={draft.codec === "raw"}
              onChange={(e) => setDraft({ ...draft, businessIdField: e.target.value })}
            />
          </Field>

          {edgeToCloud && (
            <Field
              label="Allow duplicates"
              hint="On: every inbound message is delivered, even byte-identical repeats (event/telemetry streams). Off: identical payloads dedup to a single delivery."
            >
              <div className="flex items-center gap-2">
                <Switch
                  checked={draft.allowDuplicates ?? false}
                  onCheckedChange={(checked) => setDraft({ ...draft, allowDuplicates: checked })}
                />
                <span className="readout text-[11px] text-ink-soft">
                  {(draft.allowDuplicates ?? false) ? "deliver every message" : "dedup identical payloads"}
                </span>
              </div>
            </Field>
          )}

          {errors.length > 0 && (
            <div className="border border-err/40 bg-err/10 px-3 py-2">
              {errors.map((e, i) => (
                <p key={i} className="readout text-[11px] leading-relaxed text-err">
                  ✕ {e}
                </p>
              ))}
            </div>
          )}
        </div>

        <DialogFooter>
          <Button variant="secondary" className="btn-hard" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button
            className="btn-hard"
            disabled={errors.length > 0 || applying || draft.type.trim() === ""}
            onClick={apply}
          >
            {applying ? "Saving…" : isEdit ? "Save changes" : "Add type"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
