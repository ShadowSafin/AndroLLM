import type { Metadata } from "next";
import Link from "next/link";
import { ArrowRight, GitCommit } from "lucide-react";
import { SectionHeading } from "@/components/marketing/section-heading";
import { Pulse } from "@/components/motion/pulse";
import { Reveal } from "@/components/motion/reveal";
import { site } from "@/lib/site";

export const metadata: Metadata = {
  title: "Changelog — AndroLLM",
  description:
    "Version history of AndroLLM: the 1.0.0 initial release, the unreleased foundation work, and the milestone phases. Keep-a-Changelog format.",
  alternates: { canonical: "/changelog" },
};

const unreleased = {
  added: [
    "Modular multi-module Gradle project with 31 modules",
    "Jetpack Compose Material 3 UI with “Parchment Ledger” design system",
    "LiteRT-LM 0.16.0 runtime integration — Kotlin-only, no native code",
    "LiteRT-LM Kotlin engine wrapper with lifecycle management",
    "OpenCL-based LiteRT GPU delegate with automatic CPU fallback",
    "Corruption recovery system: NaN/INF logits, invalid tokens, GPU device-lost",
    "Multi-turn chat via KV-cache diff-based continuation",
    ".litertlm container validation (LiteRtValidator + ModelInspector) — pure Kotlin metadata parser",
    "Memory estimation utility (MemoryEstimator.kt)",
    "Model catalog system with 5 supported architectures",
    "Search, filter, sort, and recommendation engine for the catalog",
    "Cloud AI integration via LiteLLM-compatible providers (Gemini, Claude, GPT, Grok, DeepSeek)",
    "Encrypted API key storage via Android Keystore AES-256/GCM (KeyCipher)",
    "SSE streaming parser for cloud provider responses with retry policy",
    "Persistent memory system: SQLite + vector embeddings + hybrid retrieval",
    "Offline voice assistant pipeline: wake word → ASR → LLM → TTS (all via sherpa-onnx)",
    "Foreground voice service with system overlay and barge-in detection",
    "Firebase Authentication: Google Sign-In + GitHub OAuth",
    "Hilt/Dagger dependency injection across all modules",
    "Room database v5 with WAL mode, 4 entities, 4 migrations",
    "DataStore preferences for user settings",
    "Navigation Compose with 15 routes and deep link support",
    "Conversation exporter and sharer utilities",
    "Markdown rendering with syntax-highlighted code blocks",
    "Developer diagnostics screen with hardware info and performance telemetry",
    "Test suite: 51 test classes covering ViewModels, repositories, parsers, catalog, and engine",
  ],
  changed: [
    "Migrated the inference engine to Google LiteRT-LM 0.16.0 — 100% Kotlin, removed the NDK/CMake native build step",
    "Package namespace migrated from io.pocketllm.* to io.androllm.* (77 Kotlin files, 16 Gradle modules)",
    "AGP updated to 8.6.0, Kotlin to 2.1.20, Compose BOM to 2024.10.00",
    "Build target raised: minSdk 28, compileSdk/targetSdk 34",
  ],
  fixed: [
    "Removed NDK toolchain and Vulkan shader compilation steps from the Windows build — pure Kotlin Gradle build",
    "Fixed UTF-16 round-trip encoding for emoji/CJK character handling",
    "Fixed context shift corruption edge case when pos_check >= nCtx - 4",
  ],
};

const initialRelease = [
  "Core application scaffolding",
  "Splash screen, onboarding flow, auth screens",
  "Home, Chat, Models, Settings, Profile, Prompts, Developer screens",
  "Basic chat UI with message bubbles and input area",
  "Room database with Conversation, Message, Model, Settings entities",
  "Model download infrastructure",
  "Firebase Authentication integration",
  "Cloud adaptive navigation (bottom bar / navigation rail)",
];

const phases = [
  { phase: "Phase 1", text: "App scaffolding, UI, architecture foundation", status: "completed" },
  { phase: "Phase 2", text: "LiteRT-LM engine, CPU/GPU backends, .litertlm model loading", status: "completed" },
  { phase: "Phase 3", text: "Cloud providers, memory system, voice assistant", status: "completed" },
];

export default function ChangelogPage() {
  return (
    <section className="container py-28 md:py-36">
      <SectionHeading
        eyebrow="Changelog"
        title={`From first commit to v${site.version}.`}
        description="The version history, verbatim from the repository's changelog. The unreleased section is where the real product lives — everything else was the runway."
      />

      <Reveal stagger={0.16} className="mx-auto mt-16 max-w-3xl space-y-12">
        <article className="rounded-card border border-[var(--line)] bg-[var(--surface)] shadow-card">
            <header className="border-b border-[var(--line)] px-6 py-5">
              <div className="flex items-center justify-between gap-3">
                <h2 className="font-serif text-2xl font-semibold text-[var(--ink)]">[Unreleased]</h2>
                <span className="inline-flex items-center gap-1.5 rounded-pill border border-[color-mix(in_srgb,var(--accent)_35%,var(--line))] bg-[color-mix(in_srgb,var(--accent)_8%,var(--surface))] px-3 py-1 font-mono text-[10px] font-bold uppercase tracking-widest text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
                  <Pulse size={4} color="var(--accent)" />
                  the product
                </span>
              </div>
              <p className="mt-1 text-sm text-[var(--muted)]">Everything that makes AndroLLM “AndroLLM” landed in this section.</p>
            </header>
            <div className="space-y-8 px-6 py-6">
              <ReleaseList title="Added" kind="added" items={unreleased.added} />
              <ReleaseList title="Changed" kind="changed" items={unreleased.changed} />
              <ReleaseList title="Fixed" kind="fixed" items={unreleased.fixed} />
            </div>
          </article>

        <article className="rounded-card border border-[var(--line)] bg-[var(--surface)] shadow-card">
            <header className="border-b border-[var(--line)] px-6 py-5">
              <h2 className="font-serif text-2xl font-semibold text-[var(--ink)]">[1.0.0] — Initial Release</h2>
              <p className="mt-1 text-sm text-[var(--muted)]">The scaffolding every product needs before it can be a product.</p>
            </header>
            <div className="px-6 py-6">
              <ReleaseList title="Added" kind="added" items={initialRelease} />
            </div>
          </article>

        <article className="rounded-card border border-[var(--line)] bg-[var(--deep)] p-6">
            <h2 className="ledger text-[var(--faint)]">Version history notes</h2>
            <ul className="mt-4 space-y-3">
              {phases.map((p) => (
                <li key={p.phase} className="flex items-center justify-between gap-4">
                  <span className="font-mono text-sm font-semibold text-[var(--ink)]">{p.phase}</span>
                  <span className="text-sm text-[var(--muted)]">{p.text}</span>
                  <span className="rounded-pill bg-[color-mix(in_srgb,var(--ok)_12%,transparent)] px-2.5 py-0.5 text-[10px] font-bold uppercase tracking-wider text-[var(--ok)]">
                    {p.status}
                  </span>
                </li>
              ))}
            </ul>
          </article>

        <div className="text-center">
          <Link
            href={site.repo}
            target="_blank"
            rel="noreferrer"
            className="group inline-flex items-center gap-2 text-sm font-semibold text-[var(--accent-deep)] transition-colors hover:text-[var(--ink)] dark:text-[var(--accent-soft)]"
          >
            <GitCommit className="size-4" />
            Follow the history on GitHub
            <ArrowRight className="size-4 transition-transform group-hover:translate-x-1" />
          </Link>
        </div>
      </Reveal>
    </section>
  );
}

function ReleaseList({ title, kind, items }: { title: string; kind: "added" | "changed" | "fixed"; items: string[] }) {
  return (
    <section aria-label={title}>
      <h3 className="text-xs font-bold uppercase tracking-widest text-[var(--faint)]">{title}</h3>
      <ul className="mt-3 space-y-2.5">
        {items.map((i) => (
          <li key={i} className="flex items-start gap-2.5 text-sm leading-relaxed text-[var(--ink-dim)]">
            <span
              className={
                kind === "added"
                  ? "mt-[0.55em] size-1.5 shrink-0 rounded-full bg-[var(--ok)]"
                  : kind === "changed"
                    ? "mt-[0.55em] size-1.5 shrink-0 rounded-full bg-[var(--warn)]"
                    : "mt-[0.55em] size-1.5 shrink-0 rounded-full bg-[var(--accent)]"
              }
              aria-hidden
            />
            {i}
          </li>
        ))}
      </ul>
    </section>
  );
}