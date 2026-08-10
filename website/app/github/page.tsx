import type { Metadata } from "next";
import Link from "next/link";
import { FileCode2, GitCommitHorizontal } from "lucide-react";
import { SectionHeading } from "@/components/marketing/section-heading";
import { Reveal } from "@/animations/reveal";
import { WordByWord } from "@/components/motion/word-by-word";
import { CursorGlow } from "@/components/motion/cursor-glow";
import { Magnetic } from "@/components/motion/magnetic";
import { HoverCard } from "@/components/motion/accordion";
import { Parallax } from "@/components/motion/parallax";
import { GitHubStats } from "@/components/marketing/github-stats";
import { site } from "@/lib/site";

export const metadata: Metadata = {
  title: "GitHub — AndroLLM",
  description:
    "The AndroLLM repository on GitHub: every line open under Apache 2.0. Star it, fork it, read the source, or send a pull request.",
  alternates: { canonical: "/github" },
};

const highlights = [
  { file: "engine/", what: "C++ layer with llama.cpp bindings, OpenCL/Vulkan GPU acceleration, gguf loader, parallel pipeline, host-side Vulkan shader compilation." },
  { file: "app/", what: "Kotlin + Compose UI: chat with streaming token output, Google Sign-In, Room-backed conversations, and the auth bridge." },
  { file: "memory/", what: "Documents, embeddings, summaries, semantic (`search`) and prefix-based (`match`) retrieval, RAG-grade context assembly." },
  { file: "voice/", what: "Wake word, speech recognition, TTS, and a foreground service with battery-saver mode — all ONNX, all offline." },
  { file: "documentation/", what: "Forty-plus markdown files: requirements, architecture, API reference, building, troubleshooting, and the release checklist." },
  { file: "website/", what: "This site — a Next.js static build whose content is derived from the docs so the site and the repo never drift." },
];

export default function GitHubPage() {
  return (
    <>
      <section className="container py-28 md:py-36">
        <CursorGlow size={460} intensity={0.22} color="var(--accent)">
          <SectionHeading
            eyebrow="GitHub"
            title={<WordByWord text="The whole project, in the open." />}
            description="One monorepo, every layer public under Apache 2.0 — engine, app, memory, voice, docs, and the website itself."
          />
        </CursorGlow>

        <Reveal className="mx-auto mt-14 max-w-4xl">
          <GitHubStats />
        </Reveal>

        <div className="mt-20 grid items-start gap-12 lg:grid-cols-[1fr_1.3fr]">
          <Reveal>
            <div>
              <p className="ledger">Repo anatomy</p>
              <h2 className="mt-3 font-serif text-3xl font-semibold text-[var(--ink)]">
                Everything lives in one place
              </h2>
              <p className="mt-4 text-sm leading-relaxed text-[var(--muted)]">
                The repository is a Gradle monorepo with the app, a C++ inference engine, memory and voice modules,
                and full documentation. Fork-friendly branches carry stable and main lines.
              </p>
              <div className="mt-6 flex flex-wrap gap-3">
                <Magnetic strength={0.18}>
                  <Link href={site.repo} target="_blank" rel="noreferrer" className="btn btn-primary">
                    <FileCode2 className="size-4" aria-hidden /> Explore the repo
                  </Link>
                </Magnetic>
                <Link href={site.issues} target="_blank" rel="noreferrer" className="btn btn-ghost">
                  Issues
                </Link>
              </div>
            </div>
          </Reveal>

          <Reveal delay={0.1}>
            <Parallax speed={0.1}>
              <div className="rounded-card border border-[var(--line)] bg-[var(--surface)] shadow-card">
                <div className="border-b border-[var(--line)] px-6 py-4">
                  <div className="flex items-center gap-3">
                    <span className="flex gap-1.5" aria-hidden>
                      <span className="size-2.5 rounded-full bg-[color-mix(in_srgb,var(--danger)_70%,white)]" />
                      <span className="size-2.5 rounded-full bg-[color-mix(in_srgb,var(--warn)_70%,white)]" />
                      <span className="size-2.5 rounded-full bg-[color-mix(in_srgb,var(--ok)_70%,white)]" />
                    </span>
                    <p className="font-mono text-xs text-[var(--muted)]">
                      {site.ghOwner}/{site.ghRepo} — main
                    </p>
                  </div>
                </div>
                <ul className="divide-y divide-[var(--line-soft)]">
                  {highlights.map((h) => (
                    <li key={h.file} className="grid gap-1 px-6 py-4 sm:grid-cols-[130px_1fr]">
                      <code className="text-[12px] font-bold text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
                        {h.file}
                      </code>
                      <p className="text-sm leading-relaxed text-[var(--muted)]">{h.what}</p>
                    </li>
                  ))}
                </ul>
              </div>
            </Parallax>
          </Reveal>
        </div>

        <div className="mt-20 grid gap-12 lg:grid-cols-2">
          <Reveal>
            <div>
              <p className="ledger">Release process</p>
              <h2 className="mt-3 font-serif text-3xl font-semibold text-[var(--ink)]">Versioned, signed, archived</h2>
              <p className="mt-4 text-sm leading-relaxed text-[var(--muted)]">
                Releases are built only from the stable branch and signed with the project keystore. Version stamps,
                per-ABI artifacts, and release notes all derive from the changelog — so the app, docs, and site report
                the same version.
              </p>
              <Link href="/downloads" className="mt-6 inline-flex items-center gap-2 text-sm font-bold text-[var(--accent-deep)] hover:underline dark:text-[var(--accent-soft)]">
                <GitCommitHorizontal className="size-3.5" aria-hidden /> See the downloads page →
              </Link>
            </div>
          </Reveal>

          <Reveal delay={0.1}>
            <div>
              <p className="ledger">Branches</p>
              <div className="mt-6 space-y-4">
                {[
                  { name: "main", text: "Unstable line. Latest in-progress work; not guaranteed to be release-tested." },
                  { name: "stable", text: "Release line. Everything moves to stable only through the release checklist." },
                  { name: "feat/*, fix/*, docs/*", text: "Working branches opened from forks. CI stages compile, test, and lint." },
                ].map((b) => (
                  <HoverCard key={b.name} className="flex items-start gap-3 p-5">
                    <code className="mt-0.5 shrink-0 rounded-md bg-[var(--accent-soft)] px-2 py-1 font-mono text-[11px] font-bold text-[var(--accent-deep)] dark:bg-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
                      {b.name}
                    </code>
                    <p className="text-sm leading-relaxed text-[var(--muted)]">{b.text}</p>
                  </HoverCard>
                ))}
              </div>
            </div>
          </Reveal>
        </div>
      </section>
    </>
  );
}