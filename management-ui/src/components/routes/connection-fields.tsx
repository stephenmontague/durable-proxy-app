"use client";

// Per-device persistent-TCP-session config: mode, role, endpoint, heartbeat, inbound type.
// Mirrors com.proxyapp.routing.TcpSession; the wizard's live validateConfig surfaces the rule
// errors (CLIENT needs host+port, SERVER needs a listen port, a persistent session needs at
// least one liveness mechanism, etc.) so these inputs stay plain.

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
import type { Heartbeat, SessionRole, TcpSession } from "@/lib/types";

const NONE = "__none__";

const DEFAULT_SESSION: TcpSession = {
  mode: "PERSISTENT",
  role: "CLIENT",
  port: null,
  heartbeat: {
    sendIntervalSec: 30,
    sendPayload: "PING",
    expectReply: "PONG",
    replyTimeoutMs: 5000,
    missThreshold: 3,
  },
};

function Mini({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <Label className="text-[10px] font-semibold uppercase tracking-[0.14em] text-ink-soft">
        {label}
      </Label>
      {children}
      {hint && <span className="text-[10px] leading-snug text-ink-faint">{hint}</span>}
    </div>
  );
}

function NumberField({
  label,
  hint,
  value,
  placeholder,
  onChange,
}: {
  label: string;
  hint?: string;
  value: number | null | undefined;
  placeholder?: string;
  onChange: (v: number | null) => void;
}) {
  return (
    <Mini label={label} hint={hint}>
      <Input
        type="number"
        className="readout h-7 text-[12px]"
        value={value ?? ""}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value === "" ? null : Number(e.target.value))}
      />
    </Mini>
  );
}

function TextField({
  label,
  hint,
  value,
  placeholder,
  onChange,
}: {
  label: string;
  hint?: string;
  value: string | null | undefined;
  placeholder?: string;
  onChange: (v: string | null) => void;
}) {
  return (
    <Mini label={label} hint={hint}>
      <Input
        className="readout h-7 text-[12px]"
        value={value ?? ""}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value === "" ? null : e.target.value)}
      />
    </Mini>
  );
}

export function ConnectionFields({
  value,
  inboundTypes,
  onChange,
}: {
  value: TcpSession | null;
  inboundTypes: string[];
  onChange: (s: TcpSession | null) => void;
}) {
  const session = value;
  const persistent = session?.mode === "PERSISTENT";
  const hb: Heartbeat = session?.heartbeat ?? {};

  const setHb = (patch: Partial<Heartbeat>) =>
    onChange({ ...(session as TcpSession), heartbeat: { ...hb, ...patch } });

  return (
    <div className="border border-hairline bg-panel-sunken/50 p-3">
      <div className="flex items-center gap-2">
        <Switch
          checked={persistent}
          onCheckedChange={(checked) => onChange(checked ? { ...DEFAULT_SESSION } : null)}
        />
        <Label className="text-[10px] font-semibold uppercase tracking-[0.14em] text-ink-soft">
          Persistent session · kept-alive socket + heartbeats
        </Label>
      </div>

      {persistent && session && (
        <div className="mt-3 flex flex-col gap-3">
          <div className="grid grid-cols-2 gap-3">
            <Mini label="Role" hint="CLIENT: proxy dials the device. SERVER: device dials in.">
              <Select
                value={session.role ?? "CLIENT"}
                onValueChange={(r) => onChange({ ...session, role: r as SessionRole })}
              >
                <SelectTrigger size="sm" className="readout text-[11px]">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="CLIENT" className="readout text-[12px]">
                    CLIENT — proxy dials
                  </SelectItem>
                  <SelectItem value="SERVER" className="readout text-[12px]">
                    SERVER — device dials in
                  </SelectItem>
                </SelectContent>
              </Select>
            </Mini>
            {session.role === "SERVER" ? (
              <>
                <NumberField
                  label="Listen port"
                  hint="Local port the proxy listens on for this device."
                  value={session.listenPort}
                  placeholder="6010"
                  onChange={(v) => onChange({ ...session, listenPort: v })}
                />
                <TextField
                  label="Handshake id"
                  hint="Only when devices share one listen port: the device sends this id (newline-terminated) as its first frame so the proxy can tell them apart."
                  value={session.handshakeId}
                  placeholder="device-a"
                  onChange={(v) => onChange({ ...session, handshakeId: v })}
                />
              </>
            ) : (
              <NumberField
                label="Device port"
                hint="Port the proxy dials on the device host."
                value={session.port}
                placeholder="9100"
                onChange={(v) => onChange({ ...session, port: v })}
              />
            )}
          </div>

          <div className="rule-label">heartbeat</div>
          <div className="grid grid-cols-2 gap-3">
            <NumberField
              label="Ping every (s)"
              hint="Outbound ping interval; blank = no ping."
              value={hb.sendIntervalSec}
              placeholder="30"
              onChange={(v) => setHb({ sendIntervalSec: v })}
            />
            <TextField
              label="Ping payload"
              hint="WireString; required when pinging."
              value={hb.sendPayload}
              placeholder="PING"
              onChange={(v) => setHb({ sendPayload: v })}
            />
            <TextField
              label="Expect reply"
              hint="WireString; blank = don't require a reply."
              value={hb.expectReply}
              placeholder="PONG"
              onChange={(v) => setHb({ expectReply: v })}
            />
            <NumberField
              label="Reply timeout (ms)"
              value={hb.replyTimeoutMs}
              placeholder="5000"
              onChange={(v) => setHb({ replyTimeoutMs: v })}
            />
            <NumberField
              label="Inbound watchdog (s)"
              hint="Expect a device frame this often; blank = off."
              value={hb.expectInboundSec}
              onChange={(v) => setHb({ expectInboundSec: v })}
            />
            <NumberField
              label="Miss threshold"
              hint="Consecutive misses before the link flips DOWN."
              value={hb.missThreshold}
              placeholder="3"
              onChange={(v) => setHb({ missThreshold: v })}
            />
          </div>

          <Mini
            label="Inbound type"
            hint="Message type for unsolicited device→cloud frames on this socket."
          >
            <Select
              value={session.inboundType ?? NONE}
              onValueChange={(t) => onChange({ ...session, inboundType: t === NONE ? null : t })}
            >
              <SelectTrigger size="sm" className="readout text-[11px]">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={NONE} className="readout text-[12px]">
                  none
                </SelectItem>
                {inboundTypes.map((t) => (
                  <SelectItem key={t} value={t} className="readout text-[12px]">
                    {t}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Mini>
        </div>
      )}
    </div>
  );
}
