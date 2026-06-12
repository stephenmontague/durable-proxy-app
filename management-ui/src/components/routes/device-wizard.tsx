"use client";

// Guided 3-step device configuration, aimed at a solutions consultant: pick a template,
// fill in site values, review every binding, apply. Validation mirrors the proxy's
// ConfigValidator so mistakes surface inline before anything is signaled.

import { useEffect, useMemo, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
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
import { WireProtocolFields } from "@/components/routes/wire-protocol-fields";
import { awaitConfigOutcome, postSignal } from "@/lib/actions";
import { matchPreset, summarizeProtocol, TCP_PRESETS } from "@/lib/tcp-presets";
import { DEVICE_TEMPLATES, materialize, type DeviceTemplateDef, type SiteValues } from "@/lib/templates";
import type { EdgeConfig, ProxyControlState, TcpProtocol } from "@/lib/types";
import { validateConfig } from "@/lib/validate";
import { cn } from "@/lib/utils";

const DEFAULT_SITE: SiteValues = {
  deviceId: "",
  baseUrl: "",
  host: "",
  basePort: 6000,
  ftpPort: 2222,
  ftpUser: "",
  ftpPassword: "",
};

function siteFromConfig(config: EdgeConfig): SiteValues {
  return {
    deviceId: config.deviceId,
    baseUrl: config.baseUrl ?? "",
    host: config.host ?? "",
    basePort: 6000,
    ftpPort: config.ftpPort ?? null,
    ftpUser: config.ftpUser ?? "",
    ftpPassword: config.ftpPassword ?? "",
  };
}

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

export function DeviceWizard({
  open,
  onOpenChange,
  state,
  editing,
  onApplied,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  state: ProxyControlState;
  editing: EdgeConfig | null;
  onApplied: () => void;
}) {
  const mode = editing ? "edit" : "template";
  const [step, setStep] = useState(1);
  const [template, setTemplate] = useState<DeviceTemplateDef | null>(null);
  const [site, setSite] = useState<SiteValues>(DEFAULT_SITE);
  const [channelOverrides, setChannelOverrides] = useState<Record<number, string>>({});
  const [showChannels, setShowChannels] = useState(false);
  const [showWire, setShowWire] = useState(false);
  const [devicePresetId, setDevicePresetId] = useState("default");
  const [deviceProtocol, setDeviceProtocol] = useState<TcpProtocol | null>(null);
  const [bindingPresetIds, setBindingPresetIds] = useState<Record<number, string>>({});
  const [bindingProtocols, setBindingProtocols] = useState<Record<number, TcpProtocol>>({});
  const [applying, setApplying] = useState(false);

  // Reset on open; in edit mode jump straight to site values.
  useEffect(() => {
    if (!open) return;
    setChannelOverrides({});
    setApplying(false);
    setShowWire(false);
    if (editing) {
      setTemplate(null);
      setSite(siteFromConfig(editing));
      setShowChannels(true);
      setDevicePresetId(matchPreset(editing.tcpProtocol));
      setDeviceProtocol(editing.tcpProtocol ?? null);
      const presetIds: Record<number, string> = {};
      const protocols: Record<number, TcpProtocol> = {};
      editing.bindings.forEach((b, i) => {
        if (b.tcpProtocol != null) {
          presetIds[i] = matchPreset(b.tcpProtocol);
          protocols[i] = b.tcpProtocol;
        }
      });
      setBindingPresetIds(presetIds);
      setBindingProtocols(protocols);
      setStep(2);
    } else {
      setTemplate(DEVICE_TEMPLATES[0] ?? null);
      setSite(DEFAULT_SITE);
      setShowChannels(false);
      setDevicePresetId("default");
      setDeviceProtocol(null);
      setBindingPresetIds({});
      setBindingProtocols({});
      setStep(1);
    }
  }, [open, editing]);

  // The draft device this wizard will sign off on.
  const draft: EdgeConfig | null = useMemo(() => {
    let base: EdgeConfig | null = null;
    if (mode === "edit" && editing) {
      base = {
        ...editing,
        deviceId: site.deviceId,
        baseUrl: site.baseUrl || null,
        host: site.host || null,
        ftpPort: site.ftpPort,
        ftpUser: site.ftpUser || null,
        ftpPassword: site.ftpPassword || null,
        bindings: editing.bindings.map((b) => ({ ...b, channel: { ...b.channel } })),
      };
    } else if (template) {
      base = materialize(template, site);
    }
    if (!base) return null;
    for (const [idx, value] of Object.entries(channelOverrides)) {
      const i = Number(idx);
      if (base.bindings[i] && value !== "") {
        base.bindings[i] = { ...base.bindings[i], channel: { ...base.bindings[i].channel, value } };
      }
    }
    base.tcpProtocol = deviceProtocol;
    base.bindings = base.bindings.map((b, i) => {
      const override = bindingPresetIds[i] && bindingPresetIds[i] !== "inherit"
        ? (bindingProtocols[i] ?? null) : null;
      return override != null ? { ...b, tcpProtocol: override } : { ...b, tcpProtocol: null };
    });
    return base;
  }, [mode, editing, template, site, channelOverrides, deviceProtocol, bindingPresetIds, bindingProtocols]);

  const errors = useMemo(() => {
    if (!draft) return [];
    const others = state.devices.filter((d) => d.deviceId !== (editing?.deviceId ?? draft.deviceId));
    return validateConfig(state.typeDirections, state.tcpPortPool, [...others, draft]);
  }, [draft, state, editing]);

  const apply = async () => {
    if (!draft) return;
    setApplying(true);
    try {
      const prevVersion = state.version;
      await postSignal("upsert-device", draft);
      const outcome = await awaitConfigOutcome(prevVersion);
      if (outcome.accepted) {
        toast.success(`Device "${draft.deviceId}" applied`, {
          description: "The proxy reconciles within a couple of seconds — watch Applied Listeners.",
        });
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

  const usesFtp = draft?.bindings.some((b) => b.transport === "FTP") ?? false;
  const tcpPool = state.tcpPortPool;
  const poolLabel = tcpPool.length > 0 ? `${tcpPool[0]}–${tcpPool[tcpPool.length - 1]}` : "—";

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[85vh] overflow-y-auto border-ink sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle className="font-mono text-sm uppercase tracking-[0.1em]">
            {mode === "edit" ? `Edit device · ${editing?.deviceId}` : "Add edge device"}
          </DialogTitle>
          <DialogDescription className="readout text-[11px] tracking-[0.08em] uppercase">
            step {step} of 3 ·{" "}
            {step === 1 ? "choose template" : step === 2 ? "site values" : "review & apply"}
          </DialogDescription>
        </DialogHeader>

        {step === 1 && mode === "template" && (
          <div className="flex flex-col gap-3">
            {DEVICE_TEMPLATES.map((t) => (
              <button
                key={t.id}
                onClick={() => setTemplate(t)}
                className={cn(
                  "panel cursor-pointer p-4 text-left transition-transform hover:-translate-y-0.5",
                  template?.id === t.id && "outline outline-2 outline-signal",
                )}
              >
                <div className="mb-1 flex items-center justify-between">
                  <span className="font-mono text-[12px] font-semibold tracking-wide">{t.name}</span>
                  <span className="chip">{t.bindings.length} bindings</span>
                </div>
                <p className="text-[12px] leading-snug text-ink-soft">{t.description}</p>
                <div className="mt-2 flex flex-wrap gap-1">
                  {t.bindings.map((b) => (
                    <span key={`${b.messageType}-${b.transport}`} className="chip">
                      {b.messageType} · {b.transport}
                    </span>
                  ))}
                </div>
              </button>
            ))}
            <div className="flex justify-end">
              <Button className="btn-hard" disabled={!template} onClick={() => setStep(2)}>
                Continue
              </Button>
            </div>
          </div>
        )}

        {step === 2 && (
          <div className="flex flex-col gap-4">
            <div className="grid grid-cols-2 gap-4">
              <Field label="Device ID" hint="A name your site knows it by.">
                <Input
                  value={site.deviceId}
                  disabled={mode === "edit"}
                  placeholder="conveyor-east"
                  onChange={(e) => setSite({ ...site, deviceId: e.target.value })}
                />
              </Field>
              <Field label="Base URL" hint="Where the device accepts HTTP commands.">
                <Input
                  value={site.baseUrl}
                  placeholder="http://192.168.1.50:8082"
                  onChange={(e) => setSite({ ...site, baseUrl: e.target.value })}
                />
              </Field>
              <Field label="Host" hint="LAN address for TCP / FTP delivery.">
                <Input
                  value={site.host}
                  placeholder="192.168.1.50"
                  onChange={(e) => setSite({ ...site, host: e.target.value })}
                />
              </Field>
              {mode === "template" && (
                <Field label="Base TCP port" hint={`Inbound ports must sit in the site pool ${poolLabel}.`}>
                  <Input
                    type="number"
                    value={site.basePort}
                    onChange={(e) => setSite({ ...site, basePort: Number(e.target.value) })}
                  />
                </Field>
              )}
              {usesFtp && (
                <>
                  <Field label="FTP port">
                    <Input
                      type="number"
                      value={site.ftpPort ?? ""}
                      onChange={(e) =>
                        setSite({ ...site, ftpPort: e.target.value === "" ? null : Number(e.target.value) })
                      }
                    />
                  </Field>
                  <Field label="FTP user">
                    <Input
                      value={site.ftpUser}
                      onChange={(e) => setSite({ ...site, ftpUser: e.target.value })}
                    />
                  </Field>
                  <Field label="FTP password">
                    <Input
                      type="password"
                      value={site.ftpPassword}
                      onChange={(e) => setSite({ ...site, ftpPassword: e.target.value })}
                    />
                  </Field>
                </>
              )}
            </div>

            <div>
              <button
                className="rule-label w-full cursor-pointer"
                onClick={() => setShowChannels((s) => !s)}
              >
                channels {showChannels ? "▾" : "▸"}
              </button>
              {showChannels && draft && (
                <div className="mt-2 flex flex-col gap-1.5">
                  {draft.bindings.map((b, i) => (
                    <div key={i} className="flex items-center gap-2">
                      <span className="readout w-44 shrink-0 truncate text-[11px]">
                        {b.messageType ?? "(resolver)"}
                      </span>
                      <span className="chip w-14 justify-center">{b.transport}</span>
                      <Input
                        className="readout h-7 text-[12px]"
                        value={channelOverrides[i] ?? b.channel.value}
                        onChange={(e) =>
                          setChannelOverrides({ ...channelOverrides, [i]: e.target.value })
                        }
                      />
                    </div>
                  ))}
                </div>
              )}
            </div>

            {draft && draft.bindings.some((b) => b.transport === "TCP") && (
              <div>
                <button
                  className="rule-label w-full cursor-pointer"
                  onClick={() => setShowWire((s) => !s)}
                >
                  wire protocol · tcp {showWire ? "▾" : "▸"}
                </button>
                {showWire && (
                  <div className="mt-2 flex flex-col gap-3">
                    <div className="flex items-center gap-3">
                      <span className="readout w-44 shrink-0 text-[11px] text-ink-soft">
                        device default
                      </span>
                      <Select
                        value={devicePresetId}
                        onValueChange={(id) => {
                          setDevicePresetId(id);
                          const preset = TCP_PRESETS.find((p) => p.id === id);
                          setDeviceProtocol(preset?.protocol ? { ...preset.protocol } : null);
                        }}
                      >
                        <SelectTrigger size="sm" className="readout flex-1 text-[11px]">
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          {TCP_PRESETS.map((p) => (
                            <SelectItem key={p.id} value={p.id} className="readout text-[12px]">
                              {p.name}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                    <p className="text-[10px] leading-snug text-ink-faint">
                      {TCP_PRESETS.find((p) => p.id === devicePresetId)?.description}
                    </p>
                    {deviceProtocol && (
                      <WireProtocolFields value={deviceProtocol} onChange={setDeviceProtocol} />
                    )}

                    {draft.bindings.map((b, i) =>
                      b.transport !== "TCP" ? null : (
                        <div key={i} className="flex flex-col gap-2">
                          <div className="flex items-center gap-3">
                            <span className="readout w-44 shrink-0 truncate text-[11px]">
                              {b.messageType ?? b.channel.value}
                            </span>
                            <Select
                              value={bindingPresetIds[i] ?? "inherit"}
                              onValueChange={(id) => {
                                setBindingPresetIds({ ...bindingPresetIds, [i]: id });
                                if (id !== "inherit") {
                                  const preset = TCP_PRESETS.find((p) => p.id === id);
                                  setBindingProtocols({
                                    ...bindingProtocols,
                                    [i]: preset?.protocol
                                      ? { ...preset.protocol }
                                      : { awaitReply: true },
                                  });
                                }
                              }}
                            >
                              <SelectTrigger size="sm" className="readout flex-1 text-[11px]">
                                <SelectValue />
                              </SelectTrigger>
                              <SelectContent>
                                <SelectItem value="inherit" className="readout text-[12px]">
                                  Inherit device default
                                </SelectItem>
                                {TCP_PRESETS.filter((p) => p.id !== "default").map((p) => (
                                  <SelectItem key={p.id} value={p.id} className="readout text-[12px]">
                                    {p.name}
                                  </SelectItem>
                                ))}
                              </SelectContent>
                            </Select>
                          </div>
                          {bindingPresetIds[i] && bindingPresetIds[i] !== "inherit" && bindingProtocols[i] && (
                            <WireProtocolFields
                              value={bindingProtocols[i]}
                              onChange={(p) => setBindingProtocols({ ...bindingProtocols, [i]: p })}
                            />
                          )}
                        </div>
                      ),
                    )}
                  </div>
                )}
              </div>
            )}

            <div className="flex justify-between">
              {mode === "template" ? (
                <Button variant="secondary" className="btn-hard" onClick={() => setStep(1)}>
                  Back
                </Button>
              ) : (
                <span />
              )}
              <Button
                className="btn-hard"
                disabled={!site.deviceId.trim()}
                onClick={() => setStep(3)}
              >
                Review
              </Button>
            </div>
          </div>
        )}

        {step === 3 && draft && (
          <div className="flex flex-col gap-4">
            <table className="w-full border-collapse">
              <thead>
                <tr className="border-b border-ink/60 text-left">
                  {["Message type", "Direction", "Via", "Channel", "Target"].map((h) => (
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
                {draft.bindings.map((b, i) => {
                  const direction = b.messageType ? state.typeDirections[b.messageType] : undefined;
                  const outbound = direction === "CLOUD_TO_EDGE";
                  const target = !outbound
                    ? "(proxy listens)"
                    : b.transport === "HTTP"
                      ? `${draft.baseUrl ?? "?"}${b.channel.value}`
                      : b.transport === "TCP"
                        ? `${draft.host ?? "?"}:${b.channel.value}`
                        : `ftp://${draft.host ?? "?"}:${draft.ftpPort ?? "?"}/${b.channel.value}`;
                  return (
                    <tr key={i} className="border-b border-hairline/70">
                      <td className="readout py-1.5 pr-2 text-[11px] font-medium">
                        {b.messageType ?? "(resolver)"}
                      </td>
                      <td className="readout py-1.5 pr-2 text-[10px] text-ink-soft">
                        {outbound ? "cloud ▸ edge" : "edge ▸ cloud"}
                      </td>
                      <td className="py-1.5 pr-2">
                        <span className="chip">{b.transport}</span>
                      </td>
                      <td className="readout py-1.5 pr-2 text-[11px]">{b.channel.value}</td>
                      <td className="readout max-w-44 truncate py-1.5 text-[11px] text-ink-soft">
                        {target}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>

            {draft.bindings.some((b) => b.transport === "TCP") && (
              <div>
                <div className="rule-label mb-1.5">tcp wire protocol</div>
                <ul className="flex flex-col gap-0.5">
                  {draft.bindings.map((b, i) =>
                    b.transport !== "TCP" ? null : (
                      <li key={i} className="readout flex items-baseline gap-2 text-[11px]">
                        <span className="w-44 shrink-0 truncate font-medium">
                          {b.messageType ?? b.channel.value}
                        </span>
                        <span className="text-ink-soft">
                          {summarizeProtocol(b.tcpProtocol ?? draft.tcpProtocol)}
                        </span>
                      </li>
                    ),
                  )}
                </ul>
              </div>
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

            <div className="flex justify-between">
              <Button variant="secondary" className="btn-hard" onClick={() => setStep(2)}>
                Back
              </Button>
              <Button
                className="btn-hard"
                disabled={errors.length > 0 || applying}
                onClick={apply}
              >
                {applying ? "Applying…" : "Apply to proxy"}
              </Button>
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
