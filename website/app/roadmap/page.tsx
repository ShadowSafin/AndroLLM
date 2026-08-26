import type { Metadata } from "next";
import Link from "next/link";
import { CheckCircle2, Circle, Sparkles, Rocket, Telescope, ArrowRight } from "lucide-react";
import { SectionHeading } from "@/components/marketing/section-heading";
import { Reveal, Stagger, StaggerItem } from "@/animations/reveal";
import { Pulse } from "@/components/motion/pulse";
import { TickerTape } from "@/components/motion/section-shell";
import { Button } from "@/components/ui/button";

export const metadata: Metadata = {
  title: "Roadmap — AndroLLM",
  description:
    "The AndroLLM development roadmap: 32 shipped foundation items, 7 in progress, and the near-term, medium-term, and long-term plans — straight from the roadmap document.",
  alternates: { canonical: "/roadmap" },
};

const done = [
  "Multi-module Gradle project structure",
  "Jetpack Compose Material 3 UI with “Parchment Ledger” theme",
  "Hilt dependency injection across all 31 modules",
  "Room database v5 with WAL mode",
  "DataStore preferences",
  "Firebase Authentication (Google + GitHub)",
  "Navigation Compose with 15 routes",
  "LiteRT-LM 0.16.0 runtime integration (100% Kotlin, no native code)",
  "LiteRT-LM engine lifecycle management (pure Kotlin)",
  "OpenCL-based LiteRT GPU delegate with automatic CPU fallback",
  "Corruption recovery (NaN/INF logits, device-lost escalation)",
  ".litertlm container validation and memory estimation",
  "Model catalog with search, filter, sort, recommendations",
  "Official model catalog (Gemma, Qwen, DeepSeek built-ins)",
  "HuggingFace model browsing and download",
  "Cloud provider abstraction via LiteLLM",
  "SSE streaming with retry policy",
  "Encrypted API key storage (Android Keystore AES-256/GCM)",
  "Robust cloud tool calling (native tools + fallback parser, conditional workflows)",
  "Cloud usage dashboard with token and cost tracking",
  "Prompt caching with provider-aware cache hints",
  "Multi-provider fallback chaining",
  "Persistent memory system (vector embeddings + hybrid retrieval)",
  "Offline voice assistant (wake word, ASR, TTS via sherpa-onnx)",
  "Foreground voice service with overlay and barge-in",
  "Markdown rendering in chat",
  "Conversation export and share",
  "Developer diagnostics screen",
  "Performance telemetry system",
  "75 test classes across all modules",
  "Adaptive navigation (phone/tablet)",
  "Model parameter sheet (temperature, top-p, etc.)",
];

const inProgress = [
  "Release build signing automation",
  "CI/CD pipeline (GitHub Actions)",
  "Play Store deployment preparation",
  "Localization / i18n support",
  "Memory UI polish (editing, categorization, tagging)",
  "Voice command extensibility framework",
  "On-device model benchmark dashboard",
];

const nearTerm = [
  { name: "NPU backend support", text: "Leverage on-device NPUs for local inference — the next accelerator after CPU and GPU" },
  { name: "Multi-language ASR", text: "Chinese, Japanese, Korean zipformer models" },
  { name: "Voice cloning TTS", text: "Pocket/ZipVoice models alongside Piper" },
  { name: "Conversation summary", text: "Auto-summarize long conversations for context-window management" },
  { name: "Function calling", text: "Expose tool-use capabilities from models that support it" },
  { name: "Widget support", text: "Home-screen widget for quick chat access" },
  { name: "Notification replies", text: "Reply to messages from system notifications" },
];

const mediumTerm = [
  { name: "ONNX Runtime backend", text: "General ML model execution beyond voice" },
  { name: "Model quantization tools", text: "Built-in Q4 → Q2 conversion for smaller devices" },
  { name: "Cross-device sync", text: "Sync conversations and memory via Firebase Firestore" },
  { name: "Shared models", text: "Share downloaded models between users on the same device" },
  { name: "API key import/export", text: "Backup and restore encrypted key store" },
  { name: "Context window optimization", text: "Automatic context truncation strategies" },
];

const longTerm = [
  { name: "Multi-modal models", text: "Vision + text models (LLaVA, etc.)" },
  { name: "Agents", text: "Autonomous task execution with tool calling" },
  { name: "Code interpreter", text: "Run Python snippets locally via PyTorch Mobile" },
  { name: "Real-time translation", text: "Live conversation translation using on-device models" },
  { name: "Medical/legal domain models", text: "Specialized fine-tuned models" },
  { name: "Enterprise deployment", text: "MDM support, custom branding, policy enforcement" },
];

const future = [
  "WebGPU backend — WebAssembly-based inference",
  "Federated learning — contribute model improvements without sharing raw data",
  "Homomorphic encryption — encrypted inference (research-stage)",
  "AR overlay — augmented reality chat interface",
  "WearOS support — companion app for smartwatches",
  "HarmonyOS port — native support for Huawei devices",
];

function GroupHead({ icon: Icon, label, count }: { icon: typeof Circle; label: string; count: string }) {
  return (
    <p className="ledger inline-flex items-center gap-2 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
      <Icon className="size-3.5" aria-hidden />
      {label} <span className="text-[var(--faint)]">· {count}</span>
    </p>
  );
}

export default function RoadmapPage() {
  return (
    <>
      <section className="container py-28 md:py-36">
        <SectionHeading
          eyebrow="Roadmap"
          title="The ledger is never closed."
          description="This is the official roadmap document, reproduced faithfully. The foundation is done — 32 items shipped — and the field is wide open for 25 more."
        />

        <Reveal className="mx-auto mt-12 max-w-3xl rounded-card border border-[color-mix(in_srgb,var(--ok)_35%,var(--line))] bg-[color-mix(in_srgb,var(--ok)_5%,var(--surface))] p-6">
          <p className="flex items-start gap-3 text-sm leading-relaxed text-[var(--ink-dim)]">
            <span className="mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full bg-[color-mix(in_srgb,var(--ok)_15%,transparent)]">
              <CheckCircle2 className="size-3.5 text-[var(--ok)]" aria-hidden />
            </span>
            <span>
              <strong className="font-semibold text-[var(--ink)]">Already live in v1.0:</strong> the roadmap document
              predates a shipped capability. The <Link href="/features#agent" className="underline decoration-[color-mix(in_srgb,var(--accent)_40%,transparent)] underline-offset-4">agent platform</Link>{" "}
              (50+ tools, planning, safety gates) is not “planned” — it ships in the app today. Function calling is likewise already exercised as grammar-constrained tool use on local models.
            </span>
          </p>
        </Reveal>

        <section className="mt-20" aria-label="In progress">
          <GroupHead icon={Circle} label="In progress" count="7 items" />
          <div className="mt-6 grid gap-4 sm:grid-cols-2">
            {inProgress.map((i, idx) => (
              <Reveal key={i} delay={idx * 0.03}>
                <div className="card flex items-start gap-3 p-5">
                  <Pulse color="var(--accent)" size={8} className="mt-1 shrink-0" />
                  <p className="text-sm leading-relaxed text-[var(--ink-dim)]">{i}</p>
                </div>
              </Reveal>
            ))}
          </div>
        </section>

        <div className="mt-20 grid gap-10 lg:grid-cols-2">
          <section aria-label="Near term">
            <GroupHead icon={Sparkles} label="Near term" count="next release cycle" />
            <ul className="mt-6 space-y-3">
              {nearTerm.map((i, idx) => (
                <Reveal key={i.name} delay={idx * 0.03}>
                  <li className="card flex h-full flex-col p-5">
                    <p className="font-serif text-base font-semibold text-[var(--ink)]">{i.name}</p>
                    <p className="mt-1 text-sm leading-relaxed text-[var(--muted)]">{i.text}</p>
                  </li>
                </Reveal>
              ))}
            </ul>
          </section>

          <section aria-label="Medium term">
            <GroupHead icon={Rocket} label="Medium term" count="3–6 months" />
            <ul className="mt-6 space-y-3">
              {mediumTerm.map((i, idx) => (
                <Reveal key={i.name} delay={idx * 0.03}>
                  <li className="card flex h-full flex-col p-5">
                    <p className="font-serif text-base font-semibold text-[var(--ink)]">{i.name}</p>
                    <p className="mt-1 text-sm leading-relaxed text-[var(--muted)]">{i.text}</p>
                  </li>
                </Reveal>
              ))}
            </ul>
          </section>
        </div>

        <section className="mt-10" aria-label="Long term">
          <GroupHead icon={Rocket} label="Long term" count="6–12 months" />
          <div className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {longTerm.map((i, idx) => (
              <Reveal key={i.name} delay={idx * 0.03}>
                <div className="card flex h-full flex-col p-5">
                  <p className="font-serif text-base font-semibold text-[var(--ink)]">{i.name}</p>
                  <p className="mt-1 text-sm leading-relaxed text-[var(--muted)]">{i.text}</p>
                </div>
              </Reveal>
            ))}
          </div>
        </section>

        <section className="mt-10" aria-label="Research and exploration">
          <GroupHead icon={Telescope} label="Research / exploration" count="6 ideas" />
          <Stagger className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3" stagger={0.07}>
            {future.map((i) => (
              <StaggerItem key={i}>
                <div className="flex h-full items-start gap-3 rounded-card border border-dashed border-[var(--line)] bg-[var(--deep)] p-5">
                  <span className="mt-1.5 size-1.5 shrink-0 rounded-full bg-[var(--faint)]" aria-hidden />
                  <p className="text-sm leading-relaxed text-[var(--muted)]">{i}</p>
                </div>
              </StaggerItem>
            ))}
          </Stagger>
        </section>

        <TickerTape
          className="mt-16"
          speed={45}
          items={["32 shipped", "7 in progress", "6 near term", "7 medium term", "6 long term"]}
        />

        <div className="mt-16 text-center">
          <Button asChild size="lg">
            <Link href="https://github.com/ShadowSafin/AndroLLM/blob/main/documentation/ROADMAP.md" target="_blank" rel="noreferrer">
              Roadmap source on GitHub
              <ArrowRight className="size-4" />
            </Link>
          </Button>
        </div>
      </section>
    </>
  );
}