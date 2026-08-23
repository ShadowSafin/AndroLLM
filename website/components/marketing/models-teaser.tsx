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
            <p className="ledger text-[var(--faint)]">Recommended start points</p>
            <div className="mt-4 space-y-3">
              {families.map((f) => (
                <div key={f.name} className="flex items-center justify-between rounded-slip border border-[var(--line-soft)] px-4 py-3">
                  <div>
                    <p className="text-sm font-semibold text-[var(--ink)]">{f.name}</p>
                    <p className="text-xs text-[var(--faint)]">{f.note}</p>
                  </div>
                  <span className="font-mono text-[11px] text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">{f.sizes}</span>
                </div>
              ))}
            </div>
          </Reveal>

          <div className="flex flex-col gap-5">
            <Reveal delay={0.08} className="grid grid-cols-2 gap-4">
              {insights.map((i) => (
                <div key={i.label} className="rounded-card border border-[var(--line)] bg-[var(--surface)] p-5 shadow-card">
                  <p className="font-serif text-2xl font-semibold text-[var(--ink)]">{i.value}</p>
                  <p className="mt-1 text-xs font-semibold uppercase tracking-wider text-[var(--faint)]">{i.label}</p>
                  <p className="mt-2 text-xs leading-relaxed text-[var(--muted)]">{i.note}</p>
                </div>
              ))}
            </Reveal>
            <Reveal delay={0.16} className="flex-1 rounded-card border border-dashed border-[var(--line)] bg-[var(--deep)] p-6">
              <p className="font-mono text-[11px] leading-relaxed text-[var(--muted)]">
                <span className="text-[var(--accent)]">✦</span> .litertlm validation · SHA-256 verify · memory estimation before load ·
                RAM-filtered catalog · HuggingFace browser · manual import · benchmark tool
              </p>
              <Link
                href="/models"
                className="group mt-5 inline-flex items-center gap-2 text-sm font-semibold text-[var(--accent-deep)] transition-colors hover:text-[var(--ink)] dark:text-[var(--accent-soft)]"
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