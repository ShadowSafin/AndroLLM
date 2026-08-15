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
    "7 curated .litertlm models across 5 architectures. Quantization guide, RAM requirements, and model management: catalog filtering, HuggingFace browsing, compatibility analysis, and benchmarking.",
  alternates: { canonical: "/models" },
};

const recommended = [
  { name: "Qwen3-0.6B", quant: "mixed int4", ram: "2 GB", note: "Agent-ready reasoning with native tool calling" },
  { name: "Gemma 3 1B", quant: "Q4", ram: "2 GB", note: "Google's compact, efficient 1B" },
  { name: "Qwen2.5-1.5B", quant: "Q8", ram: "4 GB", note: "Blazing-fast everyday assistant" },
  { name: "Qwen2-0.5B", quant: "int8", ram: "2 GB", note: "The low-RAM champion" },
  { name: "DeepSeek R1", quant: "mixed int4", ram: "4 GB", note: "Reasoning distilled for on-device use" },
  { name: "Gemma Embedding", quant: "embedding", ram: "2 GB", note: "Local embeddings for memory retrieval" },
];

const quantRows = [
  { label: "fp16", bits: "~16", reduction: "1×", quality: "Minimal loss", verdict: "Highest quality, largest download" },
  { label: "int8", bits: "~8", reduction: "2×", quality: "Minimal loss", verdict: "Best quality/size balance for 1B+" },
  { label: "mixed int4", bits: "~4", reduction: "4×", quality: "Low–medium", verdict: "Sweet spot for mobile" },
  { label: "int4", bits: "~4", reduction: "4×", quality: "Medium loss", verdict: "Tight RAM situations" },
];

const sizeGuide = [
  { model: "Qwen2-0.5B", format: "int8", size: "~0.5 GB", ram: "2 GB", best: "Everyday chat on any device" },
  { model: "Qwen3-0.6B", format: "mixed int4", size: "~475 MB", ram: "2 GB", best: "Agent & tool calling" },
  { model: "Gemma 3 1B", format: "Q4", size: "~560 MB", ram: "2 GB", best: "Google's compact all-rounder" },
  { model: "Gemma Embedding", format: "embedding", size: "~0.5 GB", ram: "2 GB", best: "Memory retrieval" },
  { model: "Qwen2.5-1.5B", format: "Q8", size: "~1.3 GB", ram: "4 GB", best: "Speed + quality" },
];

const catalogFacts = [
  { icon: Database, value: 7, label: "models in the shipped catalog", note: "litert-community on HuggingFace & ModelScope" },
  { icon: Cpu, value: 5, label: "architectures supported", note: "gemma3, gemma4, gemma-embedding, qwen2, qwen3" },
  { icon: Gauge, value: 6, label: "models marked recommended", note: "RAM-filtered for real devices" },
  { icon: MemoryStick, value: 3, label: "Qwen-family models", note: "the largest family in the catalog" },
];

const features = [
  { title: "Compatibility analyzer", text: "Before you download: will it fit in RAM? Will it GPU-accelerate on this device?" },
  { title: "RAM-filtered recommendations", text: "The catalog filters by your device's memory so you only see models that run." },
  { title: ".litertlm validation", text: "Every file is validated before load — LlmMetadata proto parsing, tensor layout check, then SHA-256 on download." },
  { title: "Benchmark tool", text: "Tokens/sec, time-to-first-token, and memory — measured on your actual device." },
];

export default function ModelsPage() {
  return (
    <>
      <section className="relative overflow-hidden py-28 md:py-36">
        <div className="container">
          <SectionHeading
            eyebrow="The model shelf"
            title="A catalog of 7 curated models, ready to run."
            description="Every model shipped in the catalog is a real, tested .litertlm container — validated, memory-estimated, and RAM-filterable on your device. All facts on this page come from the shipped catalog."
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
          <SectionHeading align="left" eyebrow="Start here" title="The 6 recommended models" description="Marked recommended in the shipped catalog — chosen for real phones, not benchmark tables. All six shown with their required RAM." />
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
          <SectionHeading eyebrow="Quantization" title="Pick your precision budget." description="Quantization shrinks the model into your RAM with a controlled quality trade-off. Mixed int4 is the mobile sweet spot — most catalog downloads default there." />

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
<th scope="col" className="px-5 py-4 text-xs font-bold uppercase tracking-wider text-[var(--faint)]">Model</th>
                  <th scope="col" className="px-5 py-4 text-xs font-bold uppercase tracking-wider text-[var(--faint)]">Format</th>
                  <th scope="col" className="px-5 py-4 text-xs font-bold uppercase tracking-wider text-[var(--faint)]">File size</th>
                  <th scope="col" className="px-5 py-4 text-xs font-bold uppercase tracking-wider text-[var(--faint)]">RAM guidance</th>
                  <th scope="col" className="px-5 py-4 text-xs font-bold uppercase tracking-wider text-[var(--faint)]">Best for</th>
                </tr>
              </thead>
              <tbody>
                {sizeGuide.map((r) => (
                  <tr key={r.model} className="border-b border-[var(--line-soft)] last:border-0">
                    <td className="px-5 py-3.5 font-semibold text-[var(--ink)]">{r.model}</td>
                    <td className="px-5 py-3.5 font-mono text-[var(--muted)]">{r.format}</td>
                    <td className="px-5 py-3.5 font-mono text-[var(--muted)]">{r.size}</td>
                    <td className="px-5 py-3.5 font-mono text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">{r.ram}</td>
                    <td className="px-5 py-3.5 text-[var(--ink-dim)]">{r.best}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </MotionReveal>

          <Reveal className="mx-auto mt-10 max-w-3xl rounded-card border border-dashed border-[var(--line)] bg-[var(--deep)] p-6">
            <p className="ledger flex items-center gap-2 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
              <HardDrive className="size-3.5" aria-hidden />
              The 2–4 GB RAM rule
            </p>
            <p className="mt-3 text-sm leading-relaxed text-[var(--muted)]">
              2 GB RAM runs the small models (Qwen3-0.6B, Gemma 3 1B, Qwen2-0.5B) — 4 GB unlocks
              Qwen2.5-1.5B and DeepSeek-class models. The app does this math for you before you download.
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
                <Link href="/docs/ai/model-formats">
                  .litertlm format guide
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