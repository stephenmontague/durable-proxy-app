"use client";

// Build-from-scratch binding editor: pick any catalog message type, a transport, and the
// channel. This is what makes the wizard domain-agnostic — the built-in template is just one
// starting point; here an operator wires up whatever types they defined in the Message types panel.

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { channelCopy } from "@/lib/channel-copy";
import type { ChannelKind, Direction, RouteBinding, Transport } from "@/lib/types";

const TRANSPORTS: Transport[] = ["HTTP", "TCP", "FTP"];
const KIND_FOR: Record<Transport, ChannelKind> = { HTTP: "PATH", TCP: "PORT", FTP: "FOLDER" };

export interface AvailableType {
  type: string;
  direction: Direction;
}

function emptyBinding(firstType: string): RouteBinding {
  return {
    messageType: firstType,
    transport: "HTTP",
    channel: { kind: "PATH", value: "" },
    resolver: null,
    tcpProtocol: null,
  };
}

export function CustomBindingsEditor({
  available,
  bindings,
  onChange,
  onDefineNewType,
}: {
  available: AvailableType[];
  bindings: RouteBinding[];
  onChange: (bindings: RouteBinding[]) => void;
  /** Open the inline "define a new message type" form for the binding at this index. */
  onDefineNewType: (index: number) => void;
}) {
  const firstType = available[0]?.type ?? "";

  const update = (index: number, patch: Partial<RouteBinding>) => {
    onChange(bindings.map((b, i) => (i === index ? { ...b, ...patch } : b)));
  };

  return (
    <div className="flex flex-col gap-2">
      <p className="text-[10px] leading-snug text-ink-faint">
        Each <span className="text-signal">channel</span> is the device↔proxy coordinate for that type
        — not the type&apos;s cloud endpoint.
      </p>
      {bindings.length === 0 && (
        <p className="readout text-[11px] text-ink-faint">
          no bindings yet — add one to route a message type through this device.
        </p>
      )}
      {bindings.map((b, i) => {
        const dir = available.find((a) => a.type === b.messageType)?.direction;
        const copy = channelCopy(b.transport, dir);
        return (
          <div key={i} className="flex flex-col gap-1">
            <div className="flex items-center gap-2">
              <Select
                value={b.messageType ?? ""}
                onValueChange={(type) =>
                  type === "__new__" ? onDefineNewType(i) : update(i, { messageType: type })
                }
              >
                <SelectTrigger size="sm" className="readout w-48 text-[11px]">
                  <SelectValue placeholder="message type" />
                </SelectTrigger>
                <SelectContent>
                  {available.map((a) => (
                    <SelectItem key={a.type} value={a.type} className="readout text-[12px]">
                      {a.type}
                    </SelectItem>
                  ))}
                  <SelectItem value="__new__" className="readout text-[12px] text-signal">
                    + Define new type…
                  </SelectItem>
                </SelectContent>
              </Select>
              <span className="readout w-20 shrink-0 text-[9px] uppercase tracking-[0.1em] text-ink-faint">
                {dir === "CLOUD_TO_EDGE" ? "cloud▸edge" : dir === "EDGE_TO_CLOUD" ? "edge▸cloud" : "—"}
              </span>
              <Select
                value={b.transport}
                onValueChange={(t) => {
                  const transport = t as Transport;
                  update(i, { transport, channel: { kind: KIND_FOR[transport], value: b.channel.value } });
                }}
              >
                <SelectTrigger size="sm" className="readout w-20 text-[11px]">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {TRANSPORTS.map((t) => (
                    <SelectItem key={t} value={t} className="readout text-[12px]">
                      {t}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <Input
                className="readout h-8 flex-1 text-[12px]"
                placeholder={copy.placeholder}
                value={b.channel.value}
                onChange={(e) => update(i, { channel: { ...b.channel, value: e.target.value } })}
              />
              <Button
                size="sm"
                variant="secondary"
                className="btn-hard px-2 font-mono text-[11px]"
                onClick={() => onChange(bindings.filter((_, j) => j !== i))}
                aria-label="remove binding"
              >
                ✕
              </Button>
            </div>
            {b.messageType && (
              <p className="pl-[2px] text-[10px] leading-snug text-ink-faint">
                <span className="text-ink-soft">{copy.role}</span> — {copy.hint}
              </p>
            )}
          </div>
        );
      })}
      <div>
        <Button
          size="sm"
          variant="secondary"
          className="btn-hard font-mono text-[10px] uppercase tracking-[0.12em]"
          disabled={available.length === 0}
          onClick={() => onChange([...bindings, emptyBinding(firstType)])}
        >
          + Add binding
        </Button>
      </div>
    </div>
  );
}
