"use client";

// The six TCP wire-protocol fields, reused for the device default and per-binding
// overrides. Values use WireString escape syntax; the wizard's live validateConfig
// surfaces parse errors, so these inputs stay plain.

import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import type { TcpProtocol } from "@/lib/types";

function WireField({
  label,
  hint,
  value,
  placeholder,
  disabled,
  onChange,
}: {
  label: string;
  hint: string;
  value: string | null | undefined;
  placeholder: string;
  disabled?: boolean;
  onChange: (v: string | null) => void;
}) {
  return (
    <div className="flex flex-col gap-1">
      <Label className="text-[10px] font-semibold uppercase tracking-[0.14em] text-ink-soft">
        {label}
      </Label>
      <Input
        className="readout h-7 text-[12px]"
        value={value ?? ""}
        placeholder={placeholder}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value === "" ? null : e.target.value)}
      />
      <span className="text-[10px] leading-snug text-ink-faint">{hint}</span>
    </div>
  );
}

export function WireProtocolFields({
  value,
  onChange,
}: {
  value: TcpProtocol;
  onChange: (p: TcpProtocol) => void;
}) {
  const awaits = value.awaitReply !== false;
  return (
    <div className="border border-hairline bg-panel-sunken/50 p-3">
      <div className="grid grid-cols-2 gap-3">
        <WireField
          label="Start delimiter"
          hint="blank = none; escapes like <VT>, <STX>, \x02"
          value={value.startDelimiter}
          placeholder="<VT>"
          onChange={(v) => onChange({ ...value, startDelimiter: v })}
        />
        <WireField
          label="End delimiter"
          hint="blank = EOF-framed (device half-closes)"
          value={value.endDelimiter}
          placeholder="<FS><CR>"
          onChange={(v) => onChange({ ...value, endDelimiter: v })}
        />
        <WireField
          label="Ack reply"
          hint="sent verbatim on accept; {activityId} substituted"
          value={value.ackReply}
          placeholder="<VT>ACK {activityId}<FS><CR>"
          onChange={(v) => onChange({ ...value, ackReply: v })}
        />
        <WireField
          label="Nak reply"
          hint="sent verbatim on reject; {reason} substituted"
          value={value.nakReply}
          placeholder="<VT>NAK {reason}<FS><CR>"
          onChange={(v) => onChange({ ...value, nakReply: v })}
        />
      </div>
      <div className="mt-3 flex items-start gap-4">
        <div className="flex items-center gap-2 pt-4">
          <Switch
            checked={awaits}
            onCheckedChange={(checked) =>
              onChange({
                ...value,
                awaitReply: checked ? true : false,
                // clearing avoids the "expectedAck is meaningless" contradiction
                expectedAck: checked ? value.expectedAck : null,
              })
            }
          />
          <Label className="text-[10px] font-semibold uppercase tracking-[0.14em] text-ink-soft">
            Wait for reply
          </Label>
        </div>
        <div className="flex-1">
          <WireField
            label="Expected ack"
            hint='matched ANYWHERE in the reply (so "ACK" matches a framed ack) — a device that naks with "NACK" needs a distinguishing string like <ACK> or the full framed ack'
            value={value.expectedAck}
            placeholder="ACK"
            disabled={!awaits}
            onChange={(v) => onChange({ ...value, expectedAck: v })}
          />
        </div>
      </div>
    </div>
  );
}
