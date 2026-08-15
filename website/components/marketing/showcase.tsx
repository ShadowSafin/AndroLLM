import Link from "next/link";
import { ArrowRight, Cloud, Cpu, Mic, Send, Wrench } from "lucide-react";
import { SectionHeading } from "@/components/marketing/section-heading";
import { Reveal } from "@/animations/reveal";
import { PhoneMockup } from "@/components/demos/phone-mockup";

const quadrants = [
  {
    icon: Send,
    title: "Multi-turn chat",
    text: "KV-cache persistence, diff-based continuation, JSON grammar decoding, streaming at up to 60 fps.",
  },
  {
    icon: Mic,
    title: "Offline voice",
    text: "“Hey Andro” wake word → 16 kHz streaming ASR → 12 local commands or the LLM → Piper TTS, with VAD barge-in.",
  },
  {
    icon: Wrench,
    title: "Agents with tools",
    text: "Plan → execute → re-plan across 50+ tools with per-tool permission toggles and spoken confirmations.",
  },
  {
    icon: Cpu,
    title: "Device-native compute",
    text: "LiteRT-LM runtime with OpenCL GPU delegate validation at runtime, XNNPACK CPU kernels, and automatic GPU→CPU fallback with corruption recovery.",
  },
  {
    icon: Cloud,
    title: "Cloud-when-you-want",
    text: "Any LiteLLM-compatible provider — Gemini, Claude, GPT, Grok, self-hosted routers — keys encrypted in the Keystore.",
    wide: true,
  },
];

export function Showcase() {
  return (
    <section className="overflow-hidden py-24 sm:py-32" aria-label="AndroLLM in action">
      <div className="container">
        <SectionHeading
          eyebrow="See it in action"
          title="Your pocket, running real intelligence."
          description="The interactive mockup below renders the actual flows the app implements — every label matches real in-app strings."
        />
      </div>

      <div className="relative mx-auto mt-16 grid max-w-6xl items-center gap-12 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        <div className="relative z-[1] order-2 mx-auto w-[min(340px,80vw)] shrink-0 lg:order-1 lg:mx-0 lg:justify-self-center">
          <div
            className="absolute -inset-16 -z-10 rounded-full opacity-70 blur-3xl"
            aria-hidden
            style={{ background: "radial-gradient(closest-side, color-mix(in srgb, var(--accent) 16%, transparent), transparent 70%)" }}
          />
          <PhoneMockup />
        </div>
        <div className="relative z-[1] order-1 z-10 lg:order-2">
          <div className="space-y-10">
            {quadrants.map((q, i) => (
              <Reveal key={q.title} delay={i * 0.05}>
                <div className="flex gap-4">
                  <span className="flex size-11 shrink-0 items-center justify-center rounded-card border border-[var(--line)] bg-[var(--surface)] text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
                    <q.icon className="size-5" aria-hidden />
                  </span>
                  <div>
                    <h3 className="font-serif text-lg font-semibold text-[var(--ink)]">{q.title}</h3>
                    <p className="mt-1 max-w-md text-sm leading-relaxed text-[var(--muted)]">{q.text}</p>
                  </div>
                </div>
              </Reveal>
            ))}
          </div>
          <Reveal delay={0.3} className="mt-10">
            <Link
              href="/features"
              className="group inline-flex items-center gap-2 text-sm font-semibold text-[var(--accent-deep)] transition-colors hover:text-[var(--ink)] dark:text-[var(--accent-soft)]"
            >
              Read the full architecture
              <ArrowRight className="size-4 transition-transform group-hover:translate-x-1" />
            </Link>
          </Reveal>
        </div>
      </div>
    </section>
  );
}