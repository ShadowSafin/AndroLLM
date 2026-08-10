import type { Metadata } from "next";
import Link from "next/link";
import { Download, Github, Archive, Smartphone, Cpu, CircleDot, HardDrive, BookOpen, FileCode2 } from "lucide-react";
import { SectionHeading } from "@/components/marketing/section-heading";
import { Reveal } from "@/animations/reveal";
import { CodeStream } from "@/components/motion/live-ticker";
import { Magnetic } from "@/components/motion/magnetic";
import { site } from "@/lib/site";
import { Button } from "@/components/ui/button";
import { CtaBand } from "@/components/marketing/cta-band";

export const metadata: Metadata = {
  title: "Downloads — AndroLLM",
  description:
    "Download AndroLLM v1.0.0 for Android 9+. Requirements, installation steps, SHA-256 verification, and building from source with Android Studio, NDK r26 and the Vulkan SDK.",
  alternates: { canonical: "/downloads" },
};

const requirements = [
  { icon: Smartphone, label: "Android version", value: "Android 9 (API 28)+", note: "Android 14 (API 34) recommended" },
  { icon: Cpu, label: "Architecture", value: "arm64-v8a only", note: "Native engine requires ARM64" },
  { icon: CircleDot, label: "RAM", value: "4 GB minimum", note: "8 GB+ recommended for 7B models" },
  { icon: HardDrive, label: "Storage", value: "Model-dependent", note: "0.25 GB (IQ3) – 14 GB (Q8 classes)" },
];

const steps = [
  { n: "1", title: "Download the APK", text: "Grab the latest arm64-v8a APK from the releases page. It's a single file — no installer, no store." },
  { n: "2", title: "Allow unknown sources", text: "Android asks you to permit installing from your browser or file manager. That's the one-time gate; the app itself never requests more than it needs." },
  { n: "3", title: "Install & launch", text: "Open the app, skip or complete sign-in (guest mode is fully functional), then pick a model from the catalog." },
  { n: "4", title: "Verify your download", text: "Compare the SHA-256 of the APK with the value published next to the release. Good hygiene regardless of where you got it." },
];

export default function DownloadsPage() {
  return (
    <>
      <section className="container py-28 md:py-36">
        <SectionHeading
          eyebrow="Downloads"
          title={`v${site.version} — one file, no strings attached.`}
          description="AndroLLM ships as a self-contained APK. No analytics, no install-time permission grab, no account required — guest mode runs everything on device."
        />

        <Reveal className="mx-auto mt-14 max-w-3xl rounded-card border border-[var(--line)] bg-[var(--surface)] p-8 text-center shadow-card">
          <span className="mx-auto flex size-14 items-center justify-center rounded-card border border-[color-mix(in_srgb,var(--accent)_30%,var(--line))] bg-[color-mix(in_srgb,var(--accent)_8%,var(--surface))] text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
            <Archive className="size-6" aria-hidden />
          </span>
          <h2 className="mt-5 font-serif text-2xl font-semibold text-[var(--ink)]">AndroLLM {site.version}</h2>
          <p className="mt-2 text-sm leading-relaxed text-[var(--muted)]">
            Package <code className="rounded bg-[var(--mutedsurface)] px-1.5 py-0.5 font-mono text-xs text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">{site.appId}</code> ·{" "}
            versionCode {site.versionCode} · minSdk {site.minSdk} · targetSdk {site.targetSdk}
          </p>
          <div className="mt-7 flex flex-wrap items-center justify-center gap-3">
            <Magnetic strength={0.18}>
              <Button asChild size="lg">
                <Link href={site.releases} target="_blank" rel="noreferrer">
                  <Download />
                  Download the APK
                </Link>
              </Button>
            </Magnetic>
            <Button asChild size="lg" variant="secondary">
              <Link href="/docs/getting-started/first-run">
                <BookOpen />
                First-run guide
              </Link>
            </Button>
          </div>
          <p className="mt-5 text-xs leading-relaxed text-[var(--faint)]">
            APKs are published on GitHub Releases. Verify the SHA-256 checksum after downloading — the app itself also verifies every model file it receives.
          </p>
        </Reveal>

        <div className="mx-auto mt-12 grid max-w-3xl gap-4 sm:grid-cols-2">
          {requirements.map((r, i) => (
            <Reveal key={r.label} delay={i * 0.04}>
              <div className="card flex h-full items-start gap-3 p-5">
                <r.icon className="mt-0.5 size-4 shrink-0 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]" aria-hidden />
                <div>
                  <p className="text-xs font-bold uppercase tracking-wider text-[var(--faint)]">{r.label}</p>
                  <p className="mt-1 text-sm font-semibold text-[var(--ink)]">{r.value}</p>
                  <p className="mt-0.5 text-xs text-[var(--muted)]">{r.note}</p>
                </div>
              </div>
            </Reveal>
          ))}
        </div>
      </section>

      <section className="border-y border-[var(--line)] bg-[var(--deep)] py-24" aria-label="Installation">
        <div className="container">
          <SectionHeading eyebrow="Installation" title="Four steps to a local brain." align="left" />
          <ol className="mt-10 grid gap-4 sm:grid-cols-2">
            {steps.map((s, i) => (
              <Reveal key={s.n} delay={i * 0.04}>
                <li className="card flex h-full gap-4 p-6">
                  <span className="flex size-9 shrink-0 items-center justify-center rounded-full border border-[var(--accent)] bg-[color-mix(in_srgb,var(--accent)_10%,var(--surface))] font-mono text-sm font-semibold text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
                    {s.n}
                  </span>
                  <div>
                    <h3 className="font-serif text-base font-semibold text-[var(--ink)]">{s.title}</h3>
                    <p className="mt-1.5 text-sm leading-relaxed text-[var(--muted)]">{s.text}</p>
                  </div>
                </li>
              </Reveal>
            ))}
          </ol>
        </div>
      </section>

      <section className="py-24" aria-label="Build from source">
        <div className="container">
          <SectionHeading
            eyebrow="Build from source"
            title="Compile it yourself."
            description="The whole project builds with Android Studio — engine, voice, agent, UI. It's a big native build: 34 Gradle modules, vendored llama.cpp and sherpa-onnx, Vulkan shaders."
          />
          <Reveal className="mx-auto mt-12 max-w-3xl">
            <CodeStream
              className="overflow-x-auto rounded-card border border-[var(--line)] bg-[var(--code-bg)] p-6 font-mono text-[13px] leading-relaxed text-[var(--ink-dim)] shadow-card"
              lines={[
                "# Prerequisites: Android Studio, JDK 17, NDK r26, Vulkan SDK",
                "git clone https://github.com/ShadowSafin/AndroLLM.git",
                "cd AndroLLM",
                "./gradlew assembleDebug",
              ]}
            />
            <p className="mt-5 flex items-center justify-center gap-2 text-sm text-[var(--muted)]">
              <FileCode2 className="size-4 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]" aria-hidden />
              Full requirements in the building guide — includes NDK 26.1.10909125, CMake 3.22+, and host-side shader compilation.
            </p>
            <div className="mt-6 flex justify-center gap-3">
              <Button asChild variant="secondary" size="lg">
                <Link href="/docs/BUILDING">
                  <BookOpen />
                  Building guide
                </Link>
              </Button>
              <Button asChild variant="ghost" size="lg">
                <Link href={site.repo} target="_blank" rel="noreferrer">
                  <Github />
                  Source on GitHub
                </Link>
              </Button>
            </div>
          </Reveal>
        </div>
      </section>

      <CtaBand />
    </>
  );
}