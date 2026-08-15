"use client";

import { useEffect, useState } from "react";
import { Cpu, Zap, Database, ServerOff, Radio } from "lucide-react";
import { cn } from "@/lib/utils";

const logLines = [
  { text: "$ androllm --model qwen3_0_6b_mixed_int4.litertlm", tone: "cmd" },
  { text: "[Engine] LiteRT-LM 0.16.0 loaded — Kotlin runtime, no native code", tone: "ok" },
  { text: "[Container] .litertlm verified ✓ · LlmMetadata proto · architecture qwen3 ✓", tone: "ok" },
  { text: "[Memory] estimated 475 MB weights + 0.3 GB KV cache", tone: "info" },
  { text: "[GPU] OpenCL delegate · Adreno (TM) 750 · gpuFree 5.1 GB", tone: "info" },
  { text: "[GPU] delegate warm-up 0.9 s", tone: "warn" },
  { text: "[RuntimeDiag] validation passed · greedy + long-context + sampling", tone: "ok" },
  { text: "[Engine] KV cache is conversation — diff-based continuation ON", tone: "info" },
  { text: "streaming → 32.1 tok/s · 34 ms/token · backend GPU delegate", tone: "accent" },
  { text: "[Memory] extracted 2 memories → vector index (local)", tone: "info" },
];

export function DesktopMockup() {
  const [shown, setShown] = useState(0);

  useEffect(() => {
    setShown(0);
    const timers: ReturnType<typeof setTimeout>[] = [];
    logLines.forEach((_, i) => timers.push(setTimeout(() => setShown(i + 1), 350 + i * 620)));
    return () => timers.forEach(clearTimeout);
  }, []);

  return (
    <div className="relative">
      <div
        className="absolute -inset-8 -z-10 rounded-full opacity-60 blur-3xl"
        aria-hidden
        style={{ background: "radial-gradient(closest-side, color-mix(in srgb, var(--accent) 14%, transparent), transparent 70%)" }}
      />
      <div className="overflow-hidden rounded-2xl border border-[var(--frame-desktop-border)] bg-[var(--frame-desktop-bg)] shadow-[var(--frame-desktop-shadow)]">
        {/* Window bar */}
        <div className="flex items-center justify-between border-b border-[var(--line)] bg-[var(--deep)] px-4 py-3">
          <div className="flex items-center gap-1.5" aria-hidden>
            <span className="size-2.5 rounded-full bg-[var(--dot-close)]" />
            <span className="size-2.5 rounded-full bg-[var(--dot-minimize)]" />
            <span className="size-2.5 rounded-full bg-[var(--dot-maximize)]" />
          </div>
          <p className="font-mono text-[10px] uppercase tracking-[0.2em] text-[var(--faint)]">
            androllm · engine session
          </p>
          <span className="inline-flex items-center gap-1.5 rounded-pill border border-[color-mix(in_srgb,var(--ok)_30%,transparent)] bg-[color-mix(in_srgb,var(--ok)_8%,transparent)] px-2.5 py-1 text-[9px] font-bold uppercase tracking-widest text-[var(--ok)]">
            <Radio className="size-2.5" />
            live
          </span>
        </div>

        {/* Body */}
        <div className="grid gap-px bg-[var(--line)] sm:grid-cols-[1fr_280px]">
          <div className="bg-[var(--canvas)] p-5 font-mono text-[11.5px] leading-[1.8]">
            {logLines.slice(0, shown).map((line, i) => (
              <p
                key={i}
                className={cn(
                  "whitespace-pre-wrap break-words",
                  line.tone === "cmd" && "font-semibold text-[var(--ink)]",
                  line.tone === "ok" && "text-[var(--ok)]",
                  line.tone === "warn" && "text-[var(--warn)]",
                  line.tone === "accent" && "font-semibold text-[var(--accent-deep)] dark:text-[var(--accent-soft)]",
                  line.tone === "info" && "text-[var(--muted)]"
                )}
              >
                {line.text}
              </p>
            ))}
            {shown < logLines.length && (
              <p className="inline-block w-2 animate-pulse text-[var(--accent)]">▍</p>
            )}
          </div>

          <aside className="hidden bg-[var(--surface)] p-5 sm:block">
            <p className="ledger text-[var(--faint)]">Live telemetry</p>
            <div className="mt-4 space-y-3">
              <GaugeRow icon={Zap} label="tokens·s⁻¹" value="32.1" trend="+4.2" />
              <GaugeRow icon={Cpu} label="backend" value="OPENCL" />
              <GaugeRow icon={Database} label="kv cache" value="512 MB" />
              <GaugeRow icon={ServerOff} label="cloud" value="0 req" />
            </div>
            <div className="mt-5">
              <div className="mb-1.5 flex justify-between font-mono text-[9px] uppercase tracking-widest text-[var(--faint)]">
                <span>gpuFree</span>
                <span>5.1 GB</span>
              </div>
              <div className="h-1 overflow-hidden rounded-full bg-[color-mix(in_srgb,var(--faint)_15%,transparent)]">
                <div className="h-full w-[64%] rounded-full bg-gradient-to-r from-[var(--accent-deep)] to-[var(--accent-soft)]" />
              </div>
            </div>
            <div className="mt-4 h-16 overflow-hidden rounded-slip border border-[var(--line)] bg-[var(--canvas)] p-2">
              <WaveForm />
            </div>
          </aside>
        </div>
      </div>
    </div>
  );
}

function GaugeRow({ icon: Icon, label, value, trend }: { icon: typeof Cpu; label: string; value: string; trend?: string }) {
  return (
    <div className="flex items-center justify-between">
      <span className="inline-flex items-center gap-2 text-[11px] text-[var(--muted)]">
        <Icon className="size-3.5 text-[var(--accent)]" />
        {label}
      </span>
      <span className="font-mono text-[11px] font-semibold text-[var(--ink)]">
        {value}
        {trend && <span className="ml-1.5 text-[9px] text-[var(--ok)]">▲{trend}</span>}
      </span>
    </div>
  );
}

function WaveForm() {
  return (
    <svg viewBox="0 0 200 44" className="h-full w-full" preserveAspectRatio="none" aria-hidden>
      <g stroke={colorMixin} strokeWidth="1.4" fill="none">
        <polyline points={wavePath + " 200,44"} />
      </g>
    </svg>
  );
}

const colorMixin = "var(--accent)";
const wavePath = Array.from({ length: 40 })
  .map((_, i) => {
    const x = i * 5;
    const raw = Math.sin(i * 0.55) * Math.cos(i * 0.23) * 18;
    return `${x},${22 + raw}`;
  })
  .join(" ");