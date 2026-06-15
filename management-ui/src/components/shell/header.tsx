"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";

const TABS = [
  { href: "/", label: "Console" },
  { href: "/config", label: "Config" },
  { href: "/temporal", label: "Temporal" },
  { href: "/dispatch", label: "Dispatch" },
];

export function Header({ address, namespace }: { address: string; namespace: string }) {
  const pathname = usePathname();
  return (
    <header className="sticky top-0 z-40 border-b-2 border-ink bg-ink text-paper">
      <div className="mx-auto flex max-w-6xl items-stretch justify-between px-6">
        <div className="flex items-center gap-3 py-3">
          <span aria-hidden className="block h-3.5 w-3.5 bg-signal" />
          <div className="flex flex-col leading-none">
            <span className="font-mono text-[15px] font-semibold tracking-[0.22em]">
              SWITCHYARD
            </span>
            <span className="mt-1 font-mono text-[9px] tracking-[0.18em] text-paper/50 uppercase">
              cloud ⇄ edge proxy console
            </span>
          </div>
        </div>
        <nav className="flex items-end gap-1">
          {TABS.map((tab) => {
            const active =
              tab.href === "/" ? pathname === "/" : pathname.startsWith(tab.href);
            return (
              <Link
                key={tab.href}
                href={tab.href}
                className={cn(
                  "px-4 pb-2.5 pt-3 font-mono text-[11px] font-medium uppercase tracking-[0.14em] transition-colors",
                  active
                    ? "border-b-[3px] border-signal bg-paper/10 text-paper"
                    : "border-b-[3px] border-transparent text-paper/55 hover:text-paper",
                )}
              >
                {tab.label}
              </Link>
            );
          })}
        </nav>
        <div className="hidden items-center md:flex">
          {/* not .chip — its unlayered light background beats Tailwind overrides */}
          <span className="inline-flex items-center gap-1 border border-paper/30 px-2 py-0.5 font-mono text-[11px] leading-[18px] text-paper/75">
            {address} · ns/{namespace}
          </span>
        </div>
      </div>
    </header>
  );
}
