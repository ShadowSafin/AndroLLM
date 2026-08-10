import { SectionHeading } from "@/components/marketing/section-heading";
import { Reveal } from "@/animations/reveal";
import { PerformanceBars } from "@/components/motion/bars-waveform";
import { performanceFacts } from "@/lib/features";
import { Cpu, Zap } from "lucide-react";

export function Performance() {
  return (
    <section className="border-t border-[var(--line)] bg-[var(--deep)] py-24 sm:py-32" aria-label="Performance">
      <div className="container">
        <SectionHeading
          eyebrow="Performance, measured"
          title="Numbers from real devices."
          description="Performance varies with chipset, model size, and memory bandwidth — these are the documented ranges from the performance guide, not marketing."
        />

        <div className="mt-14 grid gap-5 lg:grid-cols-2">
          <Reveal className="overflow-hidden rounded-card border border-[var(--line)] bg-[var(--surface)] shadow-card">
            <div className="flex items-center justify-between border-b border-[var(--line)] px-5 py-4">
              <h3 className="flex items-center gap-2 font-serif text-lg font-semibold text-[var(--ink)]">
                <Zap className="size-4 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]" />
                Generation speed (7B Q4 class models)
              </h3>
            </div>
            <table className="w-full border-collapse text-sm">
              <thead>
                <tr className="border-b border-[var(--line-soft)] text-left">
                  <th scope="col" className="px-5 py-3 text-xs font-semibold uppercase tracking-wider text-[var(--faint)]">Device tier</th>
                  <th scope="col" className="px-5 py-3 text-xs font-semibold uppercase tracking-wider text-[var(--faint)]">Vulkan GPU</th>
                  <th scope="col" className="px-5 py-3 text-xs font-semibold uppercase tracking-wider text-[var(--faint)]">CPU only</th>
                </tr>
              </thead>
              <tbody>
                {performanceFacts.backends.map((r) => (
                  <tr key={r.device} className="border-b border-[var(--line-soft)] last:border-0">
                    <td className="px-5 py-3.5 font-medium text-[var(--ink-dim)]">{r.device}</td>
                    <td className="px-5 py-3.5 font-mono text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">{r.vulkan}</td>
                    <td className="px-5 py-3.5 font-mono text-[var(--muted)]">{r.cpu}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Reveal>

          <Reveal delay={0.1} className="overflow-hidden rounded-card border border-[var(--line)] bg-[var(--surface)] shadow-card">
            <div className="flex items-center justify-between border-b border-[var(--line)] px-5 py-4">
              <h3 className="flex items-center gap-2 font-serif text-lg font-semibold text-[var(--ink)]">
                <Cpu className="size-4 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]" />
                Model load times
              </h3>
            </div>
            <div className="px-5 py-4">
              <PerformanceBars
                bars={performanceFacts.loads.map((r) => ({
                  label: r.model,
                  value: Math.min(100, 8 + (parseInt(r.vulkan.match(/\d+(?:\.\d+)?/)?.[0] ?? "0", 10) ?? 0) * 6),
                  right: `GPU ${r.vulkan} · CPU ${r.cpu}`,
                }))}
              />
              <p className="mt-5 border-t border-[var(--line-soft)] pt-4 font-mono text-[11px] text-[var(--faint)]">
                Measured on modern flagships with Vulkan; CPU-only older devices fall back to NEON + KleidiAI microkernels.
              </p>
            </div>
          </Reveal>
        </div>
      </div>
    </section>
  );
}