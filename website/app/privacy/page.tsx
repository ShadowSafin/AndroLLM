import type { Metadata } from "next";
import Link from "next/link";
import { ShieldCheck, Lock, Eye, Cpu, Radio, Trash2, Database, KeyRound } from "lucide-react";
import { SectionHeading } from "@/components/marketing/section-heading";
import { Reveal } from "@/animations/reveal";
import { WordByWord } from "@/components/motion/word-by-word";
import { CursorGlow } from "@/components/motion/cursor-glow";
import { HoverCard } from "@/components/motion/accordion";
import { AnimatedUnderline } from "@/components/motion/animated-underline";
import { site } from "@/lib/site";

export const metadata: Metadata = {
  title: "Privacy — AndroLLM",
  description:
    "The AndroLLM privacy policy: everything stays on your device unless you choose otherwise. Zero analytics, zero telemetry, no advertising identifiers.",
  alternates: { canonical: "/privacy" },
};

const onDevice = [
  { icon: Cpu, title: "Local inference", text: "Model binaries live in your app's private storage. Computation happens on your CPU/GPU — no network transmission." },
  { icon: Database, title: "Conversations", text: "Stored in the local Room database. Gone when you delete them — there is no automatic upload." },
  { icon: Eye, title: "Voice audio", text: "Processed entirely on-device: wake word, speech recognition, and text-to-speech use bundled ONNX models. Nothing is transmitted." },
  { icon: KeyRound, title: "Memory", text: "Embeddings and vectors live in a local SQLite database with an in-memory index." },
];

const mayLeave = [
  { icon: Radio, title: "Cloud AI providers (opt-in)", text: "Messages go only to the provider you connected. Keys are encrypted with AES-256/GCM in the Android Keystore and decrypted at request time." },
  { icon: Lock, title: "Firebase Authentication (optional)", text: "Sign-in via Google or GitHub uses Firebase tokens; display name and email are stored in Firestore only if you sign in. Guest mode is always available." },
  { icon: Database, title: "Model downloads", text: "Downloads from HuggingFace over HTTPS only. Provider discovery hits /v1/models on endpoints you configured." },
];

const never = [
  "Conversation content in local mode",
  "Voice audio recordings",
  "Analytics or tracking services — crash logs stay on-device",
  "Usage statistics sent to third parties",
  "Advertising identifiers",
];

const controls = [
  { action: "Delete all conversations", where: "Settings → Storage → Clear cache" },
  { action: "Delete all memories", where: "Settings → On-device Memory → Delete all" },
  { action: "Disable voice assistant", where: "Settings → Voice Assistant → Toggle off" },
  { action: "Disable cloud mode", where: "Settings → Cloud Providers → Toggle off" },
  { action: "Remove an API key", where: "Settings → Cloud Providers → Edit provider" },
  { action: "Delete your account", where: "Firebase console (for authenticated users)" },
  { action: "Export conversation history", where: "Chat drawer → Export conversation" },
  { action: "Revoke microphone permission", where: "Android Settings → Apps → AndroLLM → Permissions" },
];

const retention = [
  { item: "Conversations", text: "Retained indefinitely until you delete them. No automatic cleanup." },
  { item: "Memory data", text: "Persist until deleted or the memory system is disabled. Summaries are stored alongside the memories they summarize." },
  { item: "Voice settings", text: "Deleted when you uninstall the app (stored in DataStore)." },
  { item: "Cache", text: "Temporary files (download progress, validation caches) are cleaned automatically." },
];

export default function PrivacyPage() {
  return (
    <>
      <section className="container py-28 md:py-36">
        <CursorGlow size={460} intensity={0.22} color="var(--accent)">
          <SectionHeading
            eyebrow="Privacy policy"
            title={<WordByWord text="Your data stays on your device." />}
            description={`Last updated August 2026 · App version ${site.version} · Package ${site.appId}. AndroLLM is designed around one principle: 0 analytics, 0 tracking, and nothing leaves the phone unless you choose otherwise.`}
          />
        </CursorGlow>

        <Reveal className="mx-auto mt-14 max-w-3xl rounded-card border border-[var(--line)] bg-[var(--surface)] p-6 shadow-card">
          <p className="ledger flex items-center gap-2 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
            <ShieldCheck className="size-3.5" aria-hidden />
            What we do NOT collect
          </p>
          <ul className="mt-4 space-y-2.5">
            {never.map((n) => (
              <li key={n} className="flex items-start gap-2.5 text-sm leading-relaxed text-[var(--ink-dim)]">
                <span className="mt-[0.55em] size-1.5 shrink-0 rounded-full bg-[var(--ok)]" aria-hidden />
                {n}
              </li>
            ))}
          </ul>
        </Reveal>

        <div className="mt-14 grid gap-8 lg:grid-cols-2">
          <section aria-label="Data that stays on device">
            <Reveal>
              <h2 className="font-serif text-2xl font-semibold text-[var(--ink)]">Data that stays on your device</h2>
              <div className="mt-6 space-y-4">
                {onDevice.map((c) => (
                  <HoverCard key={c.title} className="flex items-start gap-4 p-5">
                    <c.icon className="mt-0.5 size-4 shrink-0 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]" aria-hidden />
                    <div>
                      <h3 className="text-sm font-semibold text-[var(--ink)]">{c.title}</h3>
                      <p className="mt-1 text-sm leading-relaxed text-[var(--muted)]">{c.text}</p>
                    </div>
                  </HoverCard>
                ))}
              </div>
            </Reveal>
          </section>

          <section aria-label="Data that may leave your device">
            <Reveal delay={0.08}>
              <h2 className="font-serif text-2xl font-semibold text-[var(--ink)]">Data that may leave your device</h2>
              <p className="mt-2 text-sm text-[var(--muted)]">Only through explicit, per-provider opt-in. Everything else runs locally.</p>
              <div className="mt-6 space-y-4">
                {mayLeave.map((c) => (
                  <HoverCard key={c.title} className="flex items-start gap-4 p-5">
                    <c.icon className="mt-0.5 size-4 shrink-0 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]" aria-hidden />
                    <div>
                      <h3 className="text-sm font-semibold text-[var(--ink)]">{c.title}</h3>
                      <p className="mt-1 text-sm leading-relaxed text-[var(--muted)]">{c.text}</p>
                    </div>
                  </HoverCard>
                ))}
              </div>
            </Reveal>
          </section>
        </div>

        <section className="mt-16" aria-label="Your controls">
          <Reveal>
            <h2 className="font-serif text-2xl font-semibold text-[var(--ink)]">Your rights and controls</h2>
            <div className="mt-6 overflow-x-auto rounded-card border border-[var(--line)] bg-[var(--surface)] shadow-card">
              <table className="w-full min-w-[540px] border-collapse text-sm">
                <thead>
                  <tr className="border-b border-[var(--line)] text-left">
                    <th scope="col" className="px-5 py-4 text-xs font-bold uppercase tracking-wider text-[var(--faint)]">Control</th>
                    <th scope="col" className="px-5 py-4 text-xs font-bold uppercase tracking-wider text-[var(--faint)]">Where</th>
                  </tr>
                </thead>
                <tbody>
                  {controls.map((c) => (
                    <tr key={c.action} className="border-b border-[var(--line-soft)] last:border-0">
                      <td className="px-5 py-3.5 font-medium text-[var(--ink-dim)]">{c.action}</td>
                      <td className="px-5 py-3.5 font-mono text-[12px] text-[var(--muted)]">{c.where}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Reveal>
        </section>

        <div className="mt-14 grid gap-8 lg:grid-cols-2">
          <section aria-label="Data retention">
            <Reveal>
              <h2 className="font-serif text-2xl font-semibold text-[var(--ink)]">Data retention</h2>
              <div className="mt-6 space-y-3">
                {retention.map((r) => (
                  <HoverCard key={r.item} className="flex items-start gap-4 p-5">
                    <Trash2 className="mt-0.5 size-4 shrink-0 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]" aria-hidden />
                    <div>
                      <h3 className="text-sm font-semibold text-[var(--ink)]">{r.item}</h3>
                      <p className="mt-1 text-sm leading-relaxed text-[var(--muted)]">{r.text}</p>
                    </div>
                  </HoverCard>
                ))}
              </div>
            </Reveal>
          </section>

          <section aria-label="Security measures">
            <Reveal delay={0.08}>
              <h2 className="font-serif text-2xl font-semibold text-[var(--ink)]">Security measures</h2>
              <div className="mt-6 space-y-3">
                {[
                  "Encryption at rest: API keys use AES-256/GCM via the Android Keystore",
                  "Databases live in the app sandbox (/data/data/io.androllm.app/)",
                  "All network traffic requires TLS 1.2+ — cleartext HTTP is disabled",
                  "Permissions minimalism: only what’s needed, requested lazily",
                  "Fully open source — the entire codebase is available for audit",
                ].map((s) => (
                  <HoverCard key={s} className="flex items-start gap-4 p-5">
                    <Lock className="mt-0.5 size-4 shrink-0 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]" aria-hidden />
                    <p className="text-sm leading-relaxed text-[var(--muted)]">{s}</p>
                  </HoverCard>
                ))}
              </div>
            </Reveal>
          </section>
        </div>

        <Reveal className="mt-14">
          <p className="max-w-3xl text-sm leading-relaxed text-[var(--muted)]">
            AndroLLM is not targeted at children under 13 and knowingly collects no personal information from children.
            This policy may be updated periodically; changes are posted in this file and in the app under Settings → About.
            For privacy questions, open an issue on{" "}
            <AnimatedUnderline>
              <Link href={site.issues} target="_blank" rel="noreferrer" className="font-medium text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
                GitHub
              </Link>
            </AnimatedUnderline>
            . The full third-party list (Firebase, HuggingFace, cloud providers, GitHub) is in the repository’s{" "}
            <AnimatedUnderline>
              <Link href="https://github.com/ShadowSafin/AndroLLM/blob/main/PRIVACY.md" target="_blank" rel="noreferrer" className="font-medium text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
                PRIVACY.md
              </Link>
            </AnimatedUnderline>
            .
          </p>
        </Reveal>
      </section>
    </>
  );
}