import type { Metadata } from "next";
import { Handshake } from "lucide-react";
import { SectionHeading } from "@/components/marketing/section-heading";
import { Reveal } from "@/animations/reveal";
import { WordByWord } from "@/components/motion/word-by-word";
import { CursorGlow } from "@/components/motion/cursor-glow";
import { ScrambleText } from "@/components/motion/scramble-text";
import { Parallax } from "@/components/motion/parallax";
import { TickerTape } from "@/components/motion/section-shell";
import { Contributors } from "@/components/marketing/contributors";

export const metadata: Metadata = {
  title: "Contributors — AndroLLM",
  description:
    "The people behind AndroLLM — contributors from the GitHub commit graph, plus the projects this build stands on.",
  alternates: { canonical: "/contributors" },
};

const thanks = [
  "llama.cpp contributors — the inference engine this app binds to",
  "ONNX Runtime team — the offline voice models",
  "Kotlin, Jetpack Compose, and Material You — a UI stack worth writing 40k lines by hand",
  "The open GGUF ecosystem — models anyone can download and run without asking",
];

export default function ContributorsPage() {
  return (
    <>
      <section className="container py-28 md:py-36">
        <CursorGlow size={460} intensity={0.22} color="var(--accent)">
          <SectionHeading
            eyebrow="Contributors"
            title={<WordByWord text="Built by many hands, for anyone." />}
            description="Every contributor lives in the repository’s commit graph. New projects grow one pull request at a time — and the first one could be yours."
          />
        </CursorGlow>

        <TickerTape
          className="mt-14"
          speed={36}
          items={["llama.cpp", "ONNX Runtime", "Kotlin", "Jetpack Compose", "Material You", "GGUF"]}
        />

        <Reveal className="mx-auto mt-14 max-w-4xl">
          <Contributors />
        </Reveal>

        <div className="mt-20 grid gap-10 lg:grid-cols-[1fr_1.3fr]">
          <Reveal>
            <div>
              <p className="ledger"><ScrambleText text="Standing on shoulders" /></p>
              <h2 className="mt-3 font-serif text-3xl font-semibold text-[var(--ink)]">Upstream thanks</h2>
              <p className="mt-4 text-sm leading-relaxed text-[var(--muted)]">
                The app binds to a stack that hundreds of maintainers keep alive. AndroLLM could not exist without
                their years of work — and every one of those projects remains fully credited in the repo.
              </p>
              <ul className="mt-6 space-y-3">
                {thanks.map((t) => (
                  <li key={t} className="flex items-start gap-2.5 text-sm leading-relaxed text-[var(--ink-dim)]">
                    <span className="mt-[0.55em] size-1.5 shrink-0 rounded-full bg-[var(--accent)]" aria-hidden />
                    {t}
                  </li>
                ))}
              </ul>
            </div>
          </Reveal>

          <Reveal delay={0.1}>
            <Parallax speed={0.1}>
              <div className="card p-7">
                <Handshake className="size-4 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]" aria-hidden />
                <h2 className="mt-4 font-serif text-2xl font-semibold text-[var(--ink)]">Want to be on this page?</h2>
                <p className="mt-3 text-sm leading-relaxed text-[var(--muted)]">
                  Pick an issue labeled engine, docs, or good-first-issue. Read the contributing guide, build locally,
                  and open a pull request. The list above is generated live from the GitHub API the moment you load it.
                </p>
                <p className="mt-3 text-sm leading-relaxed text-[var(--muted)]">
                  Large language models and the Android NDK have a well-known appetite for compute — generous machines,
                  patience, and good coffee are recommended.
                </p>
                <p className="mt-6 text-xs text-[var(--faint)]">
                  Contributor data comes from the public contributors endpoint, per-page top 12, cached by the browser.
                </p>
              </div>
            </Parallax>
          </Reveal>
        </div>
      </section>
    </>
  );
}