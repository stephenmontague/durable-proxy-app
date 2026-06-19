import type { Metadata } from "next";
import { IBM_Plex_Mono, Space_Grotesk } from "next/font/google";
import { Toaster } from "@/components/ui/sonner";
import { Header } from "@/components/shell/header";
import "./globals.css";

const display = Space_Grotesk({
  variable: "--font-display",
  subsets: ["latin"],
});

const plexMono = IBM_Plex_Mono({
  variable: "--font-plex-mono",
  subsets: ["latin"],
  weight: ["400", "500", "600"],
});

export const metadata: Metadata = {
  title: "Switchyard — Cloud↔Edge Proxy Console",
  description: "Operations console for the Temporal-backed cloud↔edge proxy",
};

// Render at request time, not build time: otherwise `next build` bakes the namespace (it has no
// env then → "default") into the static shell, and every `next start` shows "default" regardless
// of the per-install env. Next 16 only surfaces TEMPORAL_NAMESPACE to the server worker via a
// .env file (see the run-ui-ns recipe), and force-dynamic makes this read it at runtime.
export const dynamic = "force-dynamic";

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  const temporalAddress = process.env.TEMPORAL_ADDRESS ?? "localhost:7233";
  const temporalNamespace = process.env.TEMPORAL_NAMESPACE ?? "default";
  return (
    <html
      lang="en"
      className={`${display.variable} ${plexMono.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col">
        <Header address={temporalAddress} namespace={temporalNamespace} />
        <main className="mx-auto w-full max-w-6xl flex-1 px-6 pb-16 pt-10">
          {children}
        </main>
        <footer className="border-t border-hairline bg-panel px-6 py-3">
          <div className="mx-auto flex max-w-6xl items-center justify-between">
            <span className="readout text-[10px] tracking-[0.16em] text-ink-faint uppercase">
              Switchyard · every command rides the egress gRPC channel — no inbound ports
            </span>
            <span className="readout text-[10px] text-ink-faint">
              {temporalAddress} · ns/{temporalNamespace}
            </span>
          </div>
        </footer>
        <Toaster position="bottom-right" />
      </body>
    </html>
  );
}
