import { ShieldCheck, Radio, Lock, Eye, Cpu, Mic, BrainCircuit } from "lucide-react";
import { Reveal } from "@/animations/reveal";

const claims = [
  { icon: Cpu, text: "LLM inference stays in the on-device LiteRT-LM runtime — zero cloud" },
  { icon: Mic, text: "Wake word → ASR → TTS all run fully offline" },
  { icon: BrainCircuit, text: "Memory lives in a local SQLite vector index" },
];

export function PrivacyBand() {
  return (
    <section className="relative overflow-hidden py-24 sm:py-32" aria-label="Privacy">
      <div
        className="absolute inset-0 -z-10 opacity-60"
        aria-hidden
        style={{
          background:
            "radial-gradient(60% 90% at 20% 20%, color-mix(in srgb, var(--accent) 8%, transparent), transparent 60%), radial-gradient(50% 80% at 80% 80%, color-mix(in srgb, var(--accent) 8%, transparent), transparent 60%)",
        }}
      />
      <div className="container">
        <Reveal className="mx-auto max-w-3xl text-center">
          <p className="font-geist inline-flex items-center justify-center gap-2 rounded-3xl border-[2px] border-gray-300/20 bg-gradient-to-tr from-zinc-300/5 via-gray-400/5 to-transparent px-5 py-2 text-sm tracking-tight uppercase text-gray-600 dark:border-white/5 dark:text-gray-400">
            <ShieldCheck className="size-3.5" aria-hidden />
            The 0-telemetry promise
          </p>
          <h2 className="text-balance bg-gradient-to-br from-black from-30% to-black/40 bg-clip-text py-3 font-geist text-3xl font-semibold leading-none tracking-tighter text-transparent sm:text-4xl md:text-5xl dark:from-white dark:to-white/40">
            Your intelligence is your business.
          </h2>
          <p className="mt-4 text-balance text-lg tracking-tight text-gray-600 dark:text-gray-400 md:text-xl">
            No analytics SDKs, no crash reporters, no tracking. Nothing leaves your phone unless you explicitly
            configure a cloud provider or MCP server.
          </p>
        </Reveal>

        <div className="mx-auto mt-12 grid max-w-4xl gap-4 sm:grid-cols-2">
          {claims.map((c, i) => (
            <Reveal key={c.text} delay={i * 0.05}>
              <div className="flex h-full items-start gap-3 rounded-card border border-[var(--line)] bg-[var(--surface)] p-5 shadow-card">
                <c.icon className="mt-0.5 size-4 shrink-0 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]" aria-hidden />
                <p className="text-sm leading-relaxed text-[var(--ink-dim)]">{c.text}</p>
              </div>
            </Reveal>
          ))}
          <Reveal delay={0.22} className="sm:col-span-2">
            <div className="flex flex-wrap items-center justify-center gap-x-8 gap-y-3 rounded-card border border-[var(--line)] bg-[var(--deep)] px-6 py-4 font-mono text-[11px] text-[var(--muted)]">
              <span className="inline-flex items-center gap-2"><Lock className="size-3.5 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]" /> AES-256/GCM keys in Android Keystore</span>
              <span className="inline-flex items-center gap-2"><Eye className="size-3.5 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]" /> 8 permissions only · requested lazily</span>
              <span className="inline-flex items-center gap-2"><Radio className="size-3.5 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]" /> TLS 1.2+ enforced · cleartext disabled</span>
            </div>
          </Reveal>
        </div>
      </div>
    </section>
  );
}