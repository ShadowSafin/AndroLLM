import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { SectionHeading } from "@/components/marketing/section-heading";
import { Reveal } from "@/animations/reveal";

const families = [
  { name: "Qwen3", sizes: "0.6B", note: "mixed int4 · agent-ready" },
  { name: "Qwen2.5", sizes: "1.5B", note: "the everyday workhorse" },
  { name: "Qwen2", sizes: "0.5B", note: "low-RAM pick" },
  { name: "Gemma 3", sizes: "1B", note: "Google build" },
  { name: "DeepSeek", sizes: "distilled", note: "reasoning on-device" },
  { name: "Gemma Embedding", sizes: "embed", note: "local memory retrieval" },
];

const insights = [
  { label: "Context pages", value: "128K", note: "long documents utterly readable" },
  { label: "RAM floor", value: "2 GB", note: "minimum for the small .litertlm models" },
  { label: "Rec. RAM", value: "4 GB", note: "unlocks 1.5B-class models with GPU delegate" },
  { label: "Cloud fallback", value: "0 ms", note: "hybrid mode: any model, local or cloud" },
];

export function ModelsTeaser() {
  return (
    <section className="py-24 sm:py-32" aria-label="Models">
      <div className="container">
        <SectionHeading
          eyebrow="The model shelf"
          title="21 curated models. 5 architectures."
          description="Every bundled .litertlm is validated, memory-estimated, and RAM-filterable on your device — from ~475 MB pocket models to 1.3 GB workhorses."
        />

        <div className="mt-14 grid gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,0.9fr)]">
          <Reveal className="rounded-card border border-[var(--line)] bg-[var(--surface)] p-6 shadow-card">
            <p className="font-geist inline-flex items-center justify-center gap-2 rounded-3xl border-[2px] border-gray-300/20 bg-gradient-to-tr from-zinc-300/5 via-gray-400/5 to-transparent px-5 py-2 text-sm tracking-tight uppercase text-gray-600 dark:border-white/5 dark:text-gray-400">Recommended start points</p>
            <div className="mt-4 space-y-3">
              {families.map((f) => (
                <div key={f.name} className="flex items-center justify-between rounded-slip border border-[var(--line-soft)] px-4 py-3">
                  <div>
                    <p className="font-geist text-sm font-semibold tracking-tight text-[var(--ink)]">{f.name}</p>
                    <p className="font-geist text-xs tracking-tight text-[var(--faint)]">{f.note}</p>
                  </div>
                  <span className="font-mono text-[11px] tracking-tight text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">{f.sizes}</span>
                </div>
              ))}
            </div>
          </Reveal>

          <div className="flex flex-col gap-5">
            <Reveal delay={0.08} className="grid grid-cols-2 gap-4">
              {insights.map((i) => (
                <div key={i.label} className="rounded-card border border-[var(--line)] bg-[var(--surface)] p-5 shadow-card">
                  <p className="font-geist text-2xl font-semibold tracking-tighter leading-none text-[var(--ink)]">{i.value}</p>
                  <p className="mt-1 font-geist text-xs font-semibold uppercase tracking-tight text-[var(--faint)]">{i.label}</p>
                  <p className="mt-2 font-geist text-xs tracking-tight leading-relaxed text-gray-600 dark:text-gray-400">{i.note}</p>
                </div>
              ))}
            </Reveal>
            <Reveal delay={0.16} className="flex-1 rounded-card border border-dashed border-[var(--line)] bg-[var(--deep)] p-6">
              <p className="font-mono text-[11px] tracking-tight leading-relaxed text-gray-600 dark:text-gray-400">
                <span className="text-[var(--accent)]">✦</span> .litertlm validation · SHA-256 verify · memory estimation before load ·
                RAM-filtered catalog · HuggingFace browser · manual import · benchmark tool
              </p>
              <Link
                href="/models"
                className="group mt-5 inline-flex items-center gap-2 font-geist text-sm font-semibold tracking-tight text-[var(--accent-deep)] transition-colors hover:text-[var(--ink)] dark:text-[var(--accent-soft)]"
              >
                Browse the model guide
                <ArrowRight className="size-4 transition-transform group-hover:translate-x-1" />
              </Link>
            </Reveal>
          </div>
        </div>
      </div>
    </section>
  );
}