"use client";

import Link from "next/link";
import { useEffect, useRef } from "react";
import { motion, useReducedMotion } from "framer-motion";
import { Button } from "@/components/ui/button";

const ease = [0.22, 1, 0.36, 1] as const;

export function Hero() {
  const reduce = useReducedMotion();
  const videoARef = useRef<HTMLVideoElement>(null);
  const videoBRef = useRef<HTMLVideoElement>(null);
  const activeRef = useRef<"A" | "B">("A");
  const tickRef = useRef<number>(0);

  // Seamless loop — crossfade two instances to mask the hard cut
  // Keeps the black-hole accretion motion premium and uninterrupted
  useEffect(() => {
    const a = videoARef.current;
    const b = videoBRef.current;
    if (!a || !b) return;

    // Respect reduced motion
    if (reduce) {
      a.pause();
      b.pause();
      return;
    }

    a.style.opacity = "1";
    b.style.opacity = "0";
    a.style.transition = "opacity 900ms ease";
    b.style.transition = "opacity 900ms ease";

    // Ensure A plays; B ready at 0
    a.play().catch(() => {});
    b.pause();
    b.currentTime = 0;

    const FADE_BEFORE_END = 0.9; // sec before end to start crossfade
    const FADE_MS = 900;
    let cooldown = false;

    const tick = () => {
      tickRef.current = requestAnimationFrame(tick);
      if (cooldown) return;

      const active = activeRef.current === "A" ? a : b;
      const idle = activeRef.current === "A" ? b : a;
      if (!active.duration || Number.isNaN(active.duration)) return;
      if (active.currentTime > active.duration - FADE_BEFORE_END) {
        cooldown = true;

        // Prepare idle at 0 and start it
        try {
          idle.currentTime = 0;
        } catch {}
        idle.play().catch(() => {});

        // Crossfade
        idle.style.opacity = "1";
        active.style.opacity = "0";

        activeRef.current = activeRef.current === "A" ? "B" : "A";

        // Cooldown until fade completes + small buffer
        setTimeout(() => {
          // Pause the faded-out video and reset it to avoid double audio drift
          active.pause();
          try {
            active.currentTime = 0;
          } catch {}
          cooldown = false;
        }, FADE_MS + 80);
      }
    };

    tickRef.current = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(tickRef.current);
  }, [reduce]);

  const fade = (delay: number) => ({
    initial: reduce ? { opacity: 1 } : { opacity: 0, y: 18 },
    animate: { opacity: 1, y: 0 },
    transition: { duration: 0.8, delay, ease },
  });

  return (
    <section
      id="hero"
      aria-label="AndroLLM — private AI for Android"
      className="relative flex min-h-[92vh] w-full items-center overflow-hidden bg-black lg:min-h-[96vh]"
    >
      {/* Full-bleed hero video — seamless loop via two-layer crossfade */}
      <div className="absolute inset-0" aria-hidden>
        <video
          ref={videoARef}
          className="absolute inset-0 h-full w-full object-cover object-center [object-position:58%_50%]"
          autoPlay
          muted
          playsInline
          preload="auto"
          crossOrigin="anonymous"
          poster="/images/hero-poster.jpg"
          src="https://violet-peaceful-nightingale-428.mypinata.cloud/ipfs/bafybeihy2asmwfo2xtw745zyxu7p355mqundh4ebsjj2hwbiv4ech2ibou"
        />
        <video
          ref={videoBRef}
          className="absolute inset-0 h-full w-full object-cover object-center opacity-0 [object-position:58%_50%]"
          muted
          playsInline
          preload="auto"
          crossOrigin="anonymous"
          poster="/images/hero-poster.jpg"
          src="https://violet-peaceful-nightingale-428.mypinata.cloud/ipfs/bafybeihy2asmwfo2xtw745zyxu7p355mqundh4ebsjj2hwbiv4ech2ibou"
        />
      </div>

      {/* Cinematic grade overlays — keep text legible, keep video premium */}
      {/* Dark wash */}
      <div aria-hidden className="absolute inset-0 bg-black/42" />
      {/* Left-to-right legibility gradient — heavy on left where text sits */}
      <div
        aria-hidden
        className="absolute inset-0"
        style={{
          background:
            "linear-gradient(to right, rgba(0,0,0,0.82) 0%, rgba(0,0,0,0.72) 32%, rgba(0,0,0,0.38) 58%, rgba(0,0,0,0.12) 78%, transparent 92%)",
        }}
      />
      {/* Bottom vignette */}
      <div
        aria-hidden
        className="absolute inset-x-0 bottom-0 h-40 bg-gradient-to-t from-black via-black/35 to-transparent"
      />
      {/* Top vignette for navbar legibility */}
      <div
        aria-hidden
        className="absolute inset-x-0 top-0 h-28 bg-gradient-to-b from-black/65 to-transparent"
      />

      {/* Content — left aligned, same typography as reference but app-viable */}
      <div className="container relative z-10 flex w-full items-center py-20 lg:py-0">
        <div className="max-w-[560px]">
          <motion.h1
            {...fade(0.12)}
            className="font-geist text-left text-[2.6rem] font-extralight leading-[0.96] tracking-[-0.034em] text-white sm:text-5xl md:text-[3.4rem] lg:text-[3.7rem]"
          >
            Private AI
            <br />
            <span className="font-extralight text-white">stays here</span>
          </motion.h1>

          <motion.p
            {...fade(0.22)}
            className="mt-6 max-w-[30rem] text-left font-geist text-[13px] leading-[1.7] tracking-[-0.01em] text-white/60 sm:text-[14px]"
          >
            Local .litertlm inference on LiteRT-LM — CPU &amp; OpenCL GPU
            accelerated — offline voice assistant, on-device agent with 50+
            tools, and persistent memory.{" "}
            <span className="font-medium tracking-tight text-white">
              Zero data leaves your phone — unless you choose.
            </span>
          </motion.p>

          <motion.div {...fade(0.32)} className="mt-8 flex flex-wrap items-center gap-3">
            <Button
              asChild
              size="lg"
              className="rounded-full bg-white px-7 font-geist text-[13px] font-medium tracking-tight text-black hover:bg-zinc-100"
            >
              <Link href="/downloads">Get started</Link>
            </Button>
            <Button
              asChild
              size="lg"
              variant="outline"
              className="rounded-full border-white/20 bg-white/5 px-7 font-geist text-[13px] font-medium tracking-tight text-white backdrop-blur hover:border-white/30 hover:bg-white/10 hover:text-white"
            >
              <Link href="/docs/getting-started/first-run">Read the maths</Link>
            </Button>
          </motion.div>

          <motion.p
            {...fade(0.42)}
            className="mt-6 max-w-[26rem] text-left font-geist text-[11px] leading-relaxed tracking-tight text-white/35"
          >
            Zero telemetry · LiteRT-LM on-device · 21 curated models · Apache 2.0
          </motion.p>

          <motion.p
            {...fade(0.48)}
            className="mt-3 hidden font-mono text-[10px] uppercase tracking-[0.12em] text-white/25 sm:block"
          >
            LIVE · on-device · gravity only
          </motion.p>
        </div>
      </div>

      {/* Bottom hairline */}
      <div aria-hidden className="absolute inset-x-0 bottom-0 h-px bg-white/[0.07]" />
    </section>
  );
}
