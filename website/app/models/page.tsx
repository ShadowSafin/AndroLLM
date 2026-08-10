import type { Metadata } from "next";
import Link from "next/link";
import { ArrowRight, BookOpen, Cpu, Database, Gauge, HardDrive, MemoryStick } from "lucide-react";
import { SectionHeading } from "@/components/marketing/section-heading";
import { Reveal } from "@/animations/reveal";
import { AnimatedCounter } from "@/components/motion/animated-counter";
import { HoverCard } from "@/components/motion/accordion";
import { Reveal as MotionReveal } from "@/components/motion/reveal";
import { site } from "@/lib/site";
import { Button } from "@/components/ui/button";

export const metadata: Metadata = {
  title: "Models — AndroLLM",
  description:
    "101 curated GGUF models across 137 supported architectures. Quantization guide, RAM requirements, and model management: catalog filtering, HuggingFace browsing, compatibility analysis, and benchmarking.",
  alternates: { canonical: "/models" },
};

const recommended = [
  { name: "Qwen3-8B", quant: "Q4_K_M", ram: "12.8 GB", note: "Strong all-rounder, open reasoning" },
  { name: "Qwen2.5-7B-Instruct", quant: "Q4_K_M", ram: "10.4 GB", note: "The mobile classic — 7B in your hand" },
  { name: "gemma-3-4b-it", quant: "Q4_K_M", ram: "6.7 GB", note: "Google's efficient multimodal-capable 4B" },
  { name: "phi-4-mini-instruct", quant: "Q4_K_M", ram: "6.7 GB", note: "Microsoft's compact, code-savvy 3.8B" },
  { name: "Qwen2.5-1.5B-Instruct", quant: "Q8_0", ram: "5.3 GB", note: "Blazing-fast everyday assistant" },
  { name: "SmolLM2-1.7B-Instruct", quant: "Q4_K_M", ram: "3.4 GB", note: "The low-RAM champion" },
];

const quantRows = [
  { label: "Q8_0", bits: "~8", reduction: "2×", quality: "Minimal loss", verdict: "Best quality/size balance for 7B+" },
  { label: "Q5_K_M", bits: "~5.5", reduction: "3×", quality: "Small loss", verdict: "Recommended for most use cases" },
  { label: "Q4_K_M", bits: "~4.5", reduction: "3.5×", quality: "Low–medium", verdict: "Sweet spot for mobile" },
  { label: "Q4_K_S", bits: "~4.0", reduction: "4×", quality: "Medium loss", verdict: "Tight RAM situations" },
  { label: "IQ3_XS", bits: "~3.25", reduction: "5×", quality: "Noticeable", verdict: "Very constrained devices" },
  { label: "IQ2_XS", bits: "~2.5", reduction: "6.5×", quality: "Significant", verdict: "Last resort" },
];

const sizeGuide = [
  { model: "0.5B", bf16: "1.0 GB", q8: "0.5 GB", q5: "0.3 GB", q4: "0.25 GB", iq3: "0.2 GB" },
  { model: "1.5B", bf16: "3.0 GB", q8: "1.5 GB", q5: "0.9 GB", q4: "0.7 GB", iq3: "0.5 GB" },
  { model: "3B", bf16: "6.0 GB", q8: "3.0 GB", q5: "1.8 GB", q4: "1.4 GB", iq3: "1.0 GB" },
  { model: "7B", bf16: "14.0 GB", q8: "7.0 GB", q5: "4.2 GB", q4: "3.3 GB", iq3: "2.4 GB" },
  { model: "8B", bf16: "16.0 GB", q8: "8.0 GB", q5: "4.8 GB", q4: "3.8 GB", iq3: "2.8 GB" },
  { model: "14B", bf16: "28.0 GB", q8: "14.0 GB", q5: "8.4 GB", q4: "6.6 GB", iq3: "4.8 GB" },
];

const catalogFacts = [
  { icon: Database, value: 101, label: "models in the shipped catalog", note: "curated from HuggingFace" },
  { icon: Cpu, value: 137, label: "llama.cpp architectures supported", note: "llama, gemma2, qwen2, deepseek, mistral, phi3 & more" },
  { icon: Gauge, value: 17, label: "models marked recommended", note: "RAM-filtered for real devices" },
  { icon: MemoryStick, value: 27, label: "Qwen-family models", note: "the largest family in the catalog" },
];

const features = [
  { title: "Compatibility analyzer", text: "Before you download: will it fit in RAM? Will it GPU-accelerate on this device?" },
  { title: "RAM-filtered recommendations", text: "The catalog filters by your device's memory so you only see models that run." },
  { title: "GGUF validation", text: "Every file is validated before load — magic bytes, version, tensor count, then SHA-256 on download." },
  { title: "Benchmark tool", text: "Tokens/sec, time-to-first-token, and memory — measured on your actual device." },
];

export default function ModelsPage() {
  return (
    <>
      <section className="relative overflow-hidden py-28 md:py-36">
        <div className="container">
          <SectionHeading
            eyebrow="The model shelf"
            title="A catalog of 101 models, ready to run."
            description="Every model shipped in the catalog is a real, tested GGUF file — validated, memory-estimated, and RAM-filterable on your device. All facts on this page come from the shipped catalog and the model support guide."
          />

          <MotionReveal stagger={0.08} className="mx-auto mt-14 grid max-w-5xl gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {catalogFacts.map((f) => (
              <div key={f.label} className="card flex h-full flex-col items-center p-6 text-center">
                <f.icon className="size-5 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]" aria-hidden />
                <p className="mt-3 font-serif text-3xl font-semibold text-[var(--ink)]">
                  <AnimatedCounter value={f.value} />
                </p>
                <p className="mt-1 text-sm font-medium text-[var(--muted)]">{f.label}</p>
                <p className="mt-1 text-xs text-[var(--faint)]">{f.note}</p>
              </div>
            ))}
          </MotionReveal>
        </div>
      </section>

      <section className="border-y border-[var(--line)] bg-[var(--deep)] py-24" aria-label="Recommended models">
        <div className="container">
          <SectionHeading align="left" eyebrow="Start here" title="The 17 recommended models" description="Marked recommended in the shipped catalog — chosen for real phones, not benchmark tables. Six of them, shown with their required RAM." />
          <div className="mt-10 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {recommended.map((m, i) => (
              <Reveal key={m.name} delay={i * 0.04}>
                <HoverCard className="flex h-full flex-col p-5">
                  <div className="flex items-start justify-between gap-3">
                    <h3 className="font-serif text-lg font-semibold text-[var(--ink)]">{m.name}</h3>
                    <span className="shrink-0 rounded-pill border border-[var(--line)] bg-[var(--mutedsurface)] px-2.5 py-1 font-mono text-[10px] font-semibold text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
                      {m.quant}
                    </span>
                  </div>
                  <p className="mt-1 text-sm text-[var(--muted)]">{m.note}</p>
                  <p className="mt-auto pt-4 text-xs text-[var(--faint)]">
                    <span className="font-mono text-[var(--ink-dim)]">{m.ram}</span> recommended RAM
                  </p>
                </HoverCard>
              </Reveal>
            ))}
          </div>
        </div>
      </section>

      <section className="py-24" aria-label="Quantization guide">
        <div className="container">
          <SectionHeading eyebrow="Quantization" title="Pick your precision budget." description="Quantization shrinks the model into your RAM with a controlled quality trade-off. Q4_K_M is the mobile sweet spot — most catalog downloads default there." />

          <MotionReveal variant="fade" className="mt-12 overflow-x-auto rounded-card border border-[var(--line)] bg-[var(--surface)] shadow-card">
            <table className="w-full min-w-[640px] border-collapse text-sm">
              <thead>
                <tr className="border-b border-[var(--line)] text-left">
                  <th scope="col" className="px-5 py-4 text-xs font-bold uppercase tracking-wider text-[var(--faint)]">Format</th>
                  <th scope="col" className="px-5 py-4 text-xs font-bold uppercase tracking-wider text-[var(--faint)]">Bits/element</th>
                  <th scope="col" className="px-5 py-4 text-xs font-bold uppercase tracking-wider text-[var(--faint)]">Size reduction</th>
                  <th scope="col" className="px-5 py-4 text-xs font-bold uppercase tracking-wider text-[var(--faint)]">Quality impact</th>
                  <th scope="col" className="px-5 py-4 text-xs font-bold uppercase tracking-wider text-[var(--faint)]">Recommendation</th>
                </tr>
              </thead>
              <tbody>
                {quantRows.map((r) => (
                  <tr key={r.label} className="border-b border-[var(--line-soft)] last:border-0">
                    <td className="px-5 py-3.5 font-mono font-semibold text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">{r.label}</td>
                    <td className="px-5 py-3.5 font-mono text-[var(--muted)]">{r.bits}</td>
                    <td className="px-5 py-3.5 font-mono text-[var(--muted)]">{r.reduction}</td>
                    <td className="px-5 py-3.5 text-[var(--ink-dim)]">{r.quality}</td>
                    <td className="px-5 py-3.5 text-[var(--ink-dim)]">{r.verdict}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </MotionReveal>

          <MotionReveal variant="fade" className="mt-12 overflow-x-auto rounded-card border border-[var(--line)] bg-[var(--surface)] shadow-card">
            <table className="w-full min-w-[640px] border-collapse text-sm">
              <thead>
                <tr className="border-b border-[var(--line)] text-left">
                  <th scope="col" className="px-5 py-4 text-xs font-bold uppercase tracking-wider text-[var(--faint)]">Size class</th>
                  <th scope="col" className="px-5 py-4 text-xs font-bold uppercase tracking-wider text-[var(--faint)]">BF16</th>
                  <th scope="col" className="px-5 py-4 text-xs font-bold uppercase tracking-wider text-[var(--faint)]">Q8_0</th>
                  <th scope="col" className="px-5 py-4 text-xs font-bold uppercase tracking-wider text-[var(--faint)]">Q5_K_M</th>
                  <th scope="col" className="px-5 py-4 text-xs font-bold uppercase tracking-wider text-[var(--faint)]">Q4_K_M</th>
                  <th scope="col" className="px-5 py-4 text-xs font-bold uppercase tracking-wider text-[var(--faint)]">IQ3_XS</th>
                </tr>
              </thead>
              <tbody>
                {sizeGuide.map((r) => (
                  <tr key={r.model} className="border-b border-[var(--line-soft)] last:border-0">
                    <td className="px-5 py-3.5 font-semibold text-[var(--ink)]">{r.model}</td>
                    <td className="px-5 py-3.5 font-mono text-[var(--muted)]">{r.bf16}</td>
                    <td className="px-5 py-3.5 font-mono text-[var(--muted)]">{r.q8}</td>
                    <td className="px-5 py-3.5 font-mono text-[var(--ink-dim)]">{r.q5}</td>
                    <td className="px-5 py-3.5 font-mono text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">{r.q4}</td>
                    <td className="px-5 py-3.5 font-mono text-[var(--muted)]">{r.iq3}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </MotionReveal>

          <Reveal className="mx-auto mt-10 max-w-3xl rounded-card border border-dashed border-[var(--line)] bg-[var(--deep)] p-6">
            <p className="ledger flex items-center gap-2 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
              <HardDrive className="size-3.5" aria-hidden />
              The 4-GB rule
            </p>
            <p className="mt-3 text-sm leading-relaxed text-[var(--muted)]">
              Available RAM ≥ 2× model BF16 size → <span className="font-mono text-[var(--ink-dim)]">Q8_0</span> · ≥ 1.5× →{" "}
              <span className="font-mono text-[var(--ink-dim)]">Q5_K_M</span> · ≥ 1.2× → <span className="font-mono text-[var(--ink-dim)]">Q4_K_M</span> ·{" "}
              below that → <span className="font-mono text-[var(--ink-dim)]">Q4_K_S / IQ3_XS</span>. The app does this math for you before you download.
            </p>
          </Reveal>
        </div>
      </section>

      <section className="border-t border-[var(--line)] bg-[var(--deep)] py-24" aria-label="Model management">
        <div className="container">
          <SectionHeading eyebrow="Inside the app" title="Download, validate, load, benchmark." description="Model management is a first-class screen, not an afterthought. These four systems are implemented in the app today." />
          <div className="mt-10 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {features.map((f, i) => (
              <Reveal key={f.title} delay={i * 0.04}>
                <div className="card h-full p-6">
                  <h3 className="font-serif text-base font-semibold text-[var(--ink)]">{f.title}</h3>
                  <p className="mt-2 text-sm leading-relaxed text-[var(--muted)]">{f.text}</p>
                </div>
              </Reveal>
            ))}
          </div>
          <Reveal className="mt-10 flex flex-col items-center gap-3 text-center">
            <p className="text-sm text-[var(--muted)]">
              Need the full picture? The model support guide covers formats, architectures, and context lengths in depth.
            </p>
            <div className="flex flex-wrap justify-center gap-3">
              <Button asChild variant="secondary" size="lg">
                <Link href="/docs/MODEL_SUPPORT">
                  <BookOpen />
                  Model Support Guide
                </Link>
              </Button>
              <Button asChild variant="ghost" size="lg">
                <Link href="/docs/ai/gguf">
                  GGUF format guide
                  <ArrowRight className="size-4" />
                </Link>
              </Button>
            </div>
          </Reveal>
        </div>
      </section>
    </>
  );
}