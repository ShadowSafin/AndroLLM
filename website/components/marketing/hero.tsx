"use client";

import Link from "next/link";
import { motion, useReducedMotion } from "framer-motion";
import { Download, Github, BookOpen, MessageCircle, Compass, ArrowRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Particles, GradientBlobs, MouseLight } from "@/components/marketing/atmosphere";
import { CountUp } from "@/components/count-up";
import { CursorGlow } from "@/components/motion/cursor-glow";
import { Magnetic } from "@/components/motion/magnetic";
import { stats, site } from "@/lib/site";

const ease = [0.22, 1, 0.36, 1] as const;

export function Hero() {
  const reduce = useReducedMotion();

  const fade = (delay: number) => ({
    initial: reduce ? { opacity: 1 } : { opacity: 0, y: 26 },
    animate: { opacity: 1, y: 0 },
    transition: { duration: 0.8, delay, ease },
  });

  return (
    <section className="relative overflow-hidden" aria-label="AndroLLM — private AI for Android">
      <GradientBlobs />
      <Particles />
      <MouseLight />
      <CursorGlow size={460} intensity={0.22} color="var(--accent)" />

      <div className="container relative z-[2] flex flex-col items-center pb-16 pt-32 text-center md:pt-44">
        <motion.div {...fade(0.05)}>
          <Badge variant="glow" className="px-4 py-1.5 text-xs">
            <span className="relative flex size-2">
              <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-[var(--accent)] opacity-60" />
              <span className="relative inline-flex size-2 rounded-full bg-[var(--accent)]" />
            </span>
            v{site.version} · 101 curated models · Apache 2.0
          </Badge>
        </motion.div>

        <motion.h1
          {...fade(0.15)}
          className="text-balance mt-8 max-w-4xl font-serif text-display-xl font-semibold text-[var(--ink)]"
        >
          Private AI.
          <br />
          <em className="text-gradient-ember not-italic font-medium">Native Android.</em>
          <br />
          Your models. Your choice.
        </motion.h1>

        <motion.p
          {...fade(0.28)}
          className="mt-7 max-w-2xl text-pretty text-base leading-relaxed text-[var(--muted)] sm:text-lg"
        >
          AndroLLM is a production-grade AI platform for Android — local GGUF inference on a
          vendored llama.cpp engine with Vulkan GPU acceleration, a fully offline voice
          assistant, an on-device AI agent with 50+ tools, persistent memory, and local
          image generation. <span className="font-semibold text-[var(--ink)]">Zero data leaves your phone — unless you choose otherwise.</span>
        </motion.p>

        <motion.div {...fade(0.4)} className="mt-10 flex flex-wrap items-center justify-center gap-3">
          <Magnetic strength={0.18}>
            <Button asChild size="lg">
              <Link href="/downloads">
                <Download />
                Download
              </Link>
            </Button>
          </Magnetic>
          <Button asChild size="lg" variant="secondary">
            <Link href={site.repo} target="_blank" rel="noreferrer">
              <Github />
              GitHub
            </Link>
          </Button>
          <Button asChild size="lg" variant="outline">
            <Link href="/docs/getting-started/first-run">
              <BookOpen />
              Documentation
            </Link>
          </Button>
          <Button asChild size="lg" variant="ghost" className="text-[var(--muted)]">
            <Link href={site.discussions} target="_blank" rel="noreferrer">
              <MessageCircle />
              Community
            </Link>
          </Button>
        </motion.div>

        <motion.div {...fade(0.5)} className="mt-6">
          <Link
            href="/roadmap"
            className="group inline-flex items-center gap-1.5 text-sm font-medium text-[var(--faint)] transition-colors hover:text-[var(--accent-deep)] dark:hover:text-[var(--accent-soft)]"
          >
            <Compass className="size-3.5" />
            Explore the roadmap
            <ArrowRight className="size-3.5 transition-transform group-hover:translate-x-0.5" />
          </Link>
        </motion.div>

        <motion.dl {...fade(0.62)} className="mt-16 grid w-full max-w-4xl grid-cols-2 gap-px overflow-hidden rounded-card border border-[var(--line)] bg-[var(--line)] shadow-card sm:grid-cols-3">
          {stats.map((s) => (
            <div key={s.label} className="flex flex-col items-center bg-[var(--surface)] px-4 py-6">
              <dt className="order-2 mx-auto mt-2 max-w-[13rem] text-xs leading-relaxed text-[var(--muted)]">
                {s.label}
                {s.note && <span className="mt-0.5 block text-[10px] text-[var(--faint)]">{s.note}</span>}
              </dt>
              <dd className="order-1 font-serif text-3xl font-semibold tracking-tight text-[var(--ink)] sm:text-4xl">
                <CountUp to={s.value} suffix={s.suffix} />
              </dd>
            </div>
          ))}
        </motion.dl>
      </div>
    </section>
  );
}