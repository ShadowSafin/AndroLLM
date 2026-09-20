"use client";

import Link from "next/link";
import { motion, useReducedMotion } from "framer-motion";
import { Download, Github } from "lucide-react";
import { Button } from "@/components/ui/button";
import { site } from "@/lib/site";

// ── Hero — centred over the site-wide sonar field ───────────────────────────
// Rewritten from the 21st.dev demo. Three things changed on the way in:
//
//  1. shadcn semantic tokens (`bg-background`, `bg-primary`,
//     `text-muted-foreground`, `ring-ring/50`) don't exist in this project's
//     Tailwind theme, so each maps to its LAYER 2 equivalent — `--canvas`,
//     `--accent`, `--ink`, `--muted`, `--line`. Buttons use the project's own
//     `Button`, which already carries the focus ring and press behaviour.
//  2. `motion/react` became `framer-motion` — the same library, already
//     installed and used across the site. No new dependency.
//  3. The demo's placeholder marketing copy is replaced with facts from
//     PRODUCT.md. Counts are deliberately absent: `lib/site.ts` and the repo
//     README disagree on model and tool totals, so nothing here quotes one.
//
// The section is intentionally transparent — it reads against the fixed sonar
// field in the root layout rather than rendering a second field of its own,
// which would misalign the two dot grids.

const ease = [0.22, 1, 0.36, 1] as const;

export function SonarHero() {
  const reduce = useReducedMotion();

  const enter = (delay: number) =>
    reduce
      ? {}
      : {
          initial: { opacity: 0, y: 14, filter: "blur(6px)" },
          animate: { opacity: 1, y: 0, filter: "blur(0px)" },
          transition: { duration: 0.6, delay, ease },
        };

  return (
    <section
      aria-label="AndroLLM — private AI for Android"
      className="relative flex min-h-[max(620px,88svh)] w-full flex-col overflow-hidden"
    >
      {/* Soft wash behind the copy so the dots never fight the headline */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 -z-10 bg-[radial-gradient(ellipse_38%_34%_at_50%_50%,var(--canvas)_0%,transparent_100%)]"
      />

      <div className="container flex w-full flex-1 flex-col items-center justify-center py-24 text-center">
        <div className="flex max-w-2xl flex-col items-center">
          <motion.p
            {...enter(0)}
            className="mb-5 inline-flex items-center gap-2 rounded-full border border-[var(--line)] bg-[color-mix(in_srgb,var(--canvas)_70%,transparent)] px-3 py-1 font-mono text-[10px] uppercase tracking-[0.12em] text-[var(--muted)] backdrop-blur"
          >
            <span aria-hidden className="size-1.5 rounded-full bg-[var(--accent)]" />
            v{site.version} · {site.abi} · Apache 2.0
          </motion.p>

          <motion.h1
            {...enter(0.08)}
            className="text-balance font-geist text-5xl font-semibold tracking-tight text-[var(--ink)] sm:text-6xl md:text-7xl"
          >
            Private AI stays here
          </motion.h1>

          <motion.p
            {...enter(0.16)}
            className="mt-6 max-w-xl text-pretty text-base text-[var(--muted)] sm:text-lg"
          >
            Local{" "}
            <code className="rounded bg-[var(--mutedsurface)] px-1.5 py-0.5 font-mono text-[0.85em] text-[var(--accent-deep)]">
              .litertlm
            </code>{" "}
            inference on Google&apos;s LiteRT-LM runtime, with CPU and OpenCL GPU
            acceleration. An offline voice assistant, an on-device agent, and
            persistent memory —{" "}
            <span className="font-medium text-[var(--ink)]">
              zero data leaves your phone unless you choose.
            </span>
          </motion.p>

          <motion.div {...enter(0.24)} className="mt-9 flex flex-wrap items-center justify-center gap-3">
            <Button asChild size="lg">
              <Link href="/downloads">
                <Download />
                Get started
              </Link>
            </Button>
            <Button asChild size="lg" variant="outline">
              <Link href={site.repo} target="_blank" rel="noreferrer">
                <Github />
                Source on GitHub
              </Link>
            </Button>
          </motion.div>

          <motion.p
            {...enter(0.32)}
            className="mt-6 font-mono text-[10px] uppercase tracking-[0.12em] text-[var(--faint)]"
          >
            Zero telemetry · GitHub Releases only · Apache 2.0
          </motion.p>
        </div>
      </div>

      <div aria-hidden className="absolute inset-x-0 bottom-0 h-px bg-[var(--line-soft)]" />
    </section>
  );
}

export default SonarHero;
