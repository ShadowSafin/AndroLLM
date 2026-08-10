import Link from "next/link";
import { ArrowRight, CheckCircle2, Circle, Sparkles } from "lucide-react";
import { SectionHeading } from "@/components/marketing/section-heading";
import { Reveal } from "@/animations/reveal";

const now = [
  { text: "Release build signing automation" },
  { text: "CI/CD pipeline (GitHub Actions)" },
  { text: "Play Store deployment preparation" },
  { text: "Localization / i18n support" },
  { text: "On-device model benchmark dashboard" },
];

const next = [
  { text: "Multi-language ASR — Chinese, Japanese, Korean zipformer models" },
  { text: "Voice cloning TTS — Pocket/ZipVoice alongside Piper" },
  { text: "Conversation summary for context-window management" },
  { text: "Home-screen widget for quick chat access" },
  { text: "QNN/NPU backend — Snapdragon NPU inference" },
];

export function RoadmapTeaser() {
  return (
    <section className="py-24 sm:py-32" aria-label="Roadmap">
      <div className="container">
        <SectionHeading
          eyebrow="What ships next"
          title="The ledger is never closed."
          description="Everything on this page is already built. The roadmap tracks what is in progress and what comes after — straight from the official roadmap document."
        />

        <div className="mx-auto mt-14 grid max-w-4xl gap-5 sm:grid-cols-2">
          <Reveal className="rounded-card border border-[var(--line)] bg-[var(--surface)] p-6 shadow-card">
            <p className="ledger flex items-center gap-2 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
              <Circle className="size-3.5" aria-hidden />
              In progress
            </p>
            <ul className="mt-5 space-y-3">
              {now.map((i) => (
                <li key={i.text} className="flex items-start gap-3 text-sm leading-relaxed text-[var(--ink-dim)]">
                  <span className="mt-1.5 size-1.5 shrink-0 rounded-full bg-[var(--accent)]" aria-hidden />
                  {i.text}
                </li>
              ))}
            </ul>
          </Reveal>

          <Reveal delay={0.08} className="rounded-card border border-[var(--line)] bg-[var(--surface)] p-6 shadow-card">
            <p className="ledger flex items-center gap-2 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
              <Sparkles className="size-3.5" aria-hidden />
              Near term
            </p>
            <ul className="mt-5 space-y-3">
              {next.map((i) => (
                <li key={i.text} className="flex items-start gap-3 text-sm leading-relaxed text-[var(--ink-dim)]">
                  <span className="mt-1.5 size-1.5 shrink-0 rounded-full bg-[var(--line-strong)]" aria-hidden />
                  {i.text}
                </li>
              ))}
            </ul>
          </Reveal>
        </div>

        <Reveal delay={0.16} className="mx-auto mt-8 flex max-w-4xl flex-wrap items-center justify-center gap-x-8 gap-y-2">
          <span className="inline-flex items-center gap-2 text-xs text-[var(--muted)]">
            <CheckCircle2 className="size-3.5 text-[var(--ok)]" aria-hidden />
            28 items already shipped, including the nine pillars on this page
          </span>
          <span className="inline-flex items-center gap-2 text-xs text-[var(--muted)]">
            <CheckCircle2 className="size-3.5 text-[var(--ok)]" aria-hidden />
            Multi-modal, code interpreter, real-time translation in long term
          </span>
        </Reveal>

        <div className="mt-10 text-center">
          <Link
            href="/roadmap"
            className="group inline-flex items-center gap-2 text-sm font-semibold text-[var(--accent-deep)] transition-colors hover:text-[var(--ink)] dark:text-[var(--accent-soft)]"
          >
            Open the full roadmap
            <ArrowRight className="size-4 transition-transform group-hover:translate-x-1" />
          </Link>
        </div>
      </div>
    </section>
  );
}