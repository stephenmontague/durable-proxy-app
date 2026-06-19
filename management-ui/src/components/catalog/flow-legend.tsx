"use client";

// A compact, non-interactive legend that anchors the two easily-confused coordinates in space: the
// device↔proxy *channel* and the proxy→cloud *cloud endpoint*. Rendered in the type form (keyed to the
// type's direction) and at the head of the device wizard's channels section (both flows, since a
// device can bind a mix of directions).

import type { Direction } from "@/lib/types";

function Node({ children }: { children: React.ReactNode }) {
  return <span className="readout text-[11px] text-ink-soft">{children}</span>;
}

function Term({ children }: { children: React.ReactNode }) {
  return (
    <span className="border border-signal/60 px-1.5 py-0.5 font-mono text-[10px] uppercase tracking-[0.08em] text-signal">
      {children}
    </span>
  );
}

/** An arrow between two nodes, optionally labelled with the coordinate that governs that hop. */
function Hop({ term }: { term?: string }) {
  return (
    <span className="flex items-center gap-1 text-ink-faint">
      <span aria-hidden className="text-[11px]">─</span>
      {term ? <Term>{term}</Term> : null}
      <span aria-hidden className="text-[11px]">→</span>
    </span>
  );
}

function Flow({ direction }: { direction: Direction }) {
  const inbound = direction === "EDGE_TO_CLOUD";
  return (
    <div className="flex flex-wrap items-center gap-2">
      <span className="readout w-20 shrink-0 text-[9px] uppercase tracking-[0.12em] text-ink-faint">
        {inbound ? "edge ▸ cloud" : "cloud ▸ edge"}
      </span>
      {inbound ? (
        <>
          <Node>device</Node>
          <Hop term="channel" />
          <Node>proxy</Node>
          <Hop term="cloud endpoint" />
          <Node>cloud</Node>
        </>
      ) : (
        <>
          <Node>cloud</Node>
          <Hop />
          <Node>proxy</Node>
          <Hop term="channel" />
          <Node>device</Node>
        </>
      )}
    </div>
  );
}

export function FlowLegend({ direction }: { direction?: Direction }) {
  return (
    <div className="flex flex-col gap-1.5 border border-hairline bg-ink/[0.03] px-3 py-2">
      {direction ? (
        <Flow direction={direction} />
      ) : (
        <>
          <Flow direction="EDGE_TO_CLOUD" />
          <Flow direction="CLOUD_TO_EDGE" />
        </>
      )}
      <p className="text-[10px] leading-snug text-ink-faint">
        <span className="text-signal">channel</span> = device↔proxy coordinate, set per device binding.{" "}
        <span className="text-signal">cloud endpoint</span> = proxy→cloud path, set per message type (inbound only).
      </p>
    </div>
  );
}
