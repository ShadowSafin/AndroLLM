import type { Metadata } from "next";
import Link from "next/link";
import { Smartphone, BrainCircuit, ShieldCheck, WifiOff, Cpu, Code2, PackageOpen, Building2 } from "lucide-react";
import { SectionHeading } from "@/components/marketing/section-heading";
import { Reveal } from "@/animations/reveal";
import { WordByWord } from "@/components/motion/word-by-word";
import { CursorGlow } from "@/components/motion/cursor-glow";
import { Magnetic } from "@/components/motion/magnetic";
import { HoverCard } from "@/components/motion/accordion";
import { site } from "@/lib/site";

export const metadata: Metadata = {
  title: "About — AndroLLM",
  description:
    "AndroLLM is a private, fully on-device AI assistant for Android — Kotlin, Jetpack Compose, llama.cpp, ONNX, and Material Material You — completely open source.",
  alternates: { canonical: "/about" },
};

const pillars = [
  { icon: Smartphone, title: "Native Android", text: "Kotlin + Jetpack Compose + Material You. No WebView, no Electron shell, no server in the middle." },
  { icon: BrainCircuit, title: "Real on-device intelligence", text: "GGUF models via llama.cpp with OpenCL and Vulkan GPU layers — the model lives on your phone." },
  { icon: WifiOff, title: "Private by default", text: "Zero analytics, zero telemetry. Conversations, memories, and voice run locally. Cloud is a manual, per-provider opt-in." },
  { icon: Code2, title: "Extensible", text: "Connect any OpenAI-compatible endpoint, bring your own GGUF, or call standardized provider APIs." },
];

const story = [
  { year: "Project start", text: "The first commit founded the monorepo: a Kotlin app, a C++ inference engine, and the documentation that grew with them." },
  { year: "The core loop", text: "GGUF loading, llama.cpp bindings, token streaming, and a Compose chat UI came together — and generations started landing live on device." },
  { year: "Memory & voice", text: "An on-device memory bank with embeddings, plus a fully offline voice assistant: wake word, speech recognition, and TTS with bundled ONNX models." },
  { year: "Today", text: "Version " + site.version + " ships a complete assistant — chat, RAG-grade memory, tools, cloud providers, offline voice — with a matching open-source website so the whole project is reviewable end-to-end." },
];

export default function AboutPage() {
  return (
    <>
      <section className="container py-28 md:py-36">
        <CursorGlow size={460} intensity={0.22} color="var(--accent)">
          <SectionHeading
            eyebrow="About"
            title={<WordByWord text="Private AI. Native Android. Your Models. Your choice." />}
            description="AndroLLM turns your phone into a personal AI server. Every byte of inference, memory, and voice processing happens on-device — because the most private cloud is the one you carry in your pocket."
          />
        </CursorGlow>

        <div className="mt-14 grid gap-6 md:grid-cols-2">
          {pillars.map((p, i) => (
            <Reveal key={p.title} delay={i * 0.07}>
              <HoverCard className="h-full p-6">
                <div className="flex items-center gap-2.5">
                  <p.icon className="size-4 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]" aria-hidden />
                  <h2 className="font-serif text-lg font-semibold text-[var(--ink)]">{p.title}</h2>
                </div>
                <p className="mt-3 text-sm leading-relaxed text-[var(--muted)]">{p.text}</p>
              </HoverCard>
            </Reveal>
          ))}
        </div>

        <div className="mt-20 grid gap-12 lg:grid-cols-[1fr_1.2fr]">
          <Reveal>
            <div>
              <p className="ledger">Stack</p>
              <h2 className="mt-3 font-serif text-3xl font-semibold text-[var(--ink)]">One phone, one stack, zero servers.</h2>
              <p className="mt-4 text-sm leading-relaxed text-[var(--muted)]">
                The whole project is a single Gradle monorepo: a Compose app, a C++ inference engine bound through JNI
                with Kotlin/Native entry points on the Rust side, and OpenCL/Vulkan-backed GPU layers. Everything is
                Apache 2.0 and fully open to audit.
              </p>
              <div className="mt-8 grid grid-cols-2 gap-3">
                {[
                  { v: "Kotlin", l: "Entire app UI & logic" },
                  { v: "C++ / llama.cpp", l: "Local GGUF inference" },
                  { v: "ONNX Runtime", l: "Voice: ASR, TTS, wake word" },
                  { v: "OpenCL · Vulkan", l: "GPU acceleration" },
                  { v: "Room · DataStore", l: "Storage & preferences" },
                  { v: "Jetpack Compose", l: "100% Material You UI" },
                ].map((t) => (
                  <HoverCard key={t.v} className="p-4">
                    <p className="font-mono text-[13px] font-bold text-[var(--ink)]">{t.v}</p>
                    <p className="mt-1 text-[12px] leading-snug text-[var(--faint)]">{t.l}</p>
                  </HoverCard>
                ))}
              </div>
            </div>
          </Reveal>

          <Reveal delay={0.1}>
            <div>
              <p className="ledger">Where it comes from</p>
              <div className="mt-6 space-y-0 border-l border-[var(--line)]">
                {story.map((s, i) => (
                  <div key={s.year} className="relative pb-8 pl-7 last:pb-0">
                    <span className="absolute -left-[4.5px] top-1.5 size-2 rounded-full bg-[var(--accent-deep)] ring-4 ring-[var(--accent-soft)] dark:bg-[var(--accent-soft)] dark:ring-[var(--accent-deep)]" aria-hidden />
                    <p className="text-xs font-bold uppercase tracking-[0.18em] text-[var(--faint)]">
                      {String(i + 1).padStart(2, "0")} · {s.year}
                    </p>
                    <p className="mt-1.5 text-sm leading-relaxed text-[var(--muted)]">{s.text}</p>
                  </div>
                ))}
              </div>
            </div>
          </Reveal>
        </div>

        <Reveal className="mx-auto mt-20 max-w-3xl">
          <div className="rounded-card border border-[var(--line)] bg-[var(--surface)] p-8 text-center shadow-card">
            <ShieldCheck className="mx-auto size-5 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]" aria-hidden />
            <h2 className="mt-4 font-serif text-2xl font-semibold text-[var(--ink)]">Fully open source</h2>
            <p className="mx-auto mt-3 max-w-xl text-sm leading-relaxed text-[var(--muted)]">
              No binaries hidden behind a walled garden. The app, engine, docs, and this website are all public on
              GitHub under the Apache 2.0 license. You can read every line, build it yourself, or change it.
            </p>
            <div className="mt-6 flex flex-wrap items-center justify-center gap-3">
              <Magnetic strength={0.18}>
                <Link
                  href={site.repo}
                  target="_blank"
                  rel="noreferrer"
                  className="btn btn-primary"
                >
                  <PackageOpen className="size-4" aria-hidden /> View the repository
                </Link>
              </Magnetic>
              <Link href="/docs" className="btn btn-ghost">
                <Cpu className="size-4" aria-hidden /> Read the docs
              </Link>
              <Link href="/downloads" className="btn btn-ghost">
                <Building2 className="size-4" aria-hidden /> Get the app
              </Link>
            </div>
          </div>
        </Reveal>
      </section>
    </>
  );
}