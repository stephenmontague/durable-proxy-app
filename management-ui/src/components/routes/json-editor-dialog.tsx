"use client";

// Advanced mode: edit the full device array as raw JSON and applyConfig in one shot.
// Same local validation as the wizard; the control workflow still has the final say.

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
import { Textarea } from "@/components/ui/textarea";
import { postSignal } from "@/lib/actions";
import type { EdgeConfig, ProxyControlState } from "@/lib/types";
import { validateConfig } from "@/lib/validate";

export function JsonEditorDialog({
  open,
  onOpenChange,
  state,
  onApplied,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  state: ProxyControlState;
  onApplied: () => void;
}) {
  const [text, setText] = useState("[]");
  const [applying, setApplying] = useState(false);

  useEffect(() => {
    if (open) setText(JSON.stringify(state.devices, null, 2));
    // Snapshot on open only — live polling must not clobber the operator's edits.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const { parsed, errors } = useMemo(() => {
    try {
      const value = JSON.parse(text) as EdgeConfig[];
      if (!Array.isArray(value)) return { parsed: null, errors: ["top level must be a JSON array of devices"] };
      return { parsed: value, errors: validateConfig(state.typeDirections, state.tcpPortPool, value) };
    } catch (e) {
      return { parsed: null, errors: [`invalid JSON: ${e instanceof Error ? e.message : e}`] };
    }
  }, [text, state]);

  const apply = async () => {
    if (!parsed) return;
    setApplying(true);
    try {
      const outcome = await postSignal("apply-config", parsed);
      if (outcome.accepted) {
        toast.success("Full config applied", { description: outcome.message });
        onApplied();
        onOpenChange(false);
      } else {
        toast.error("Control workflow rejected the config", { description: outcome.message });
      }
    } catch (e) {
      toast.error("Apply failed", { description: e instanceof Error ? e.message : String(e) });
    } finally {
      setApplying(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="flex max-h-[85vh] flex-col border-ink sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle className="font-mono text-sm uppercase tracking-[0.1em]">
            Raw config · applyConfig
          </DialogTitle>
          <DialogDescription className="text-[12px]">
            Replaces the entire device list in one signal. For edge cases the wizard doesn&apos;t
            cover.
          </DialogDescription>
        </DialogHeader>
        <Textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          spellCheck={false}
          // field-sizing-fixed: the editor must scroll, not grow the dialog past the viewport
          className="readout min-h-0 flex-1 basis-72 resize-none overflow-y-auto field-sizing-fixed border-hairline-strong text-[12px] leading-relaxed"
        />
        {errors.length > 0 && (
          <div className="max-h-28 overflow-y-auto border border-err/40 bg-err/10 px-3 py-2">
            {errors.map((e, i) => (
              <p key={i} className="readout text-[11px] leading-relaxed text-err">
                ✕ {e}
              </p>
            ))}
          </div>
        )}
        <DialogFooter>
          <Button variant="secondary" className="btn-hard" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button className="btn-hard" disabled={errors.length > 0 || applying} onClick={apply}>
            {applying ? "Applying…" : "Apply full config"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
