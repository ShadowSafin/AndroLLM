import type { Metadata } from "next";
import Link from "next/link";
import { ArrowRight, BookOpen, Download } from "lucide-react";
import { SectionHeading } from "@/components/marketing/section-heading";
import { FeatureGrid } from "@/components/marketing/feature-grid";
import { DetailSections } from "@/components/marketing/detail-sections";
import { Reveal } from "@/animations/reveal";
import { HoverCard } from "@/components/motion/accordion";
import { RevealStagger } from "@/components/motion/reveal";
import { uiFeatures } from "@/lib/features";
import { Button } from "@/components/ui/button";
import { CtaBand } from "@/components/marketing/cta-band";

export const metadata: Metadata = {
  title: "Features — AndroLLM",
  description:
    "Every capability of AndroLLM: local GGUF inference on llama.cpp with Vulkan, offline voice, an agent platform with 50+ tools, MCP & UI automation, persistent memory, on-device image generation, cloud-when-you-want, and a local-first privacy guarantee.",
  alternates: { canonical: "/features" },
};

export default function FeaturesPage() {
  return (
    <>
      <section className="container pb-8 pt-28 md:pt-36">
        <SectionHeading
          eyebrow="Feature index"
          title="Everything AndroLLM ships."
          description="A complete inventory of the installed capabilities — every item verified against the app source, not a spec sheet. Nothing on this page is aspirational."
        />
      </section>

      <FeatureGrid />

      <DetailSections />

      <section className="border-y border-[var(--line)] bg-[var(--deep)] py-24 sm:py-32" aria-label="App experience">
        <div className="container">
          <SectionHeading
            eyebrow="Inside the UX"
            title="The Parchment Ledger experience."
            description="The interface is a design system — adaptive navigation, streaming markdown, a model manager with a 101-model catalog, and security handled at the architecture level."
          />
          <RevealStagger className="mt-14 grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
            {uiFeatures.map((f, i) => (
              <article key={f.id} className={i === 3 ? "lg:col-span-2 lg:grid-cols-subgrid" : ""}>
                <HoverCard className="flex h-full flex-col p-6">
                  <span className="flex size-11 items-center justify-center rounded-card border border-[color-mix(in_srgb,var(--accent)_30%,var(--line))] bg-[color-mix(in_srgb,var(--accent)_8%,var(--surface))] text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
                    <f.icon className="size-5" aria-hidden />
                  </span>
                  <h3 className="mt-5 font-serif text-xl font-semibold tracking-tight text-[var(--ink)]">{f.name}</h3>
                  <p className="mt-1 text-sm text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">{f.tagline}</p>
                  <p className="mt-3 text-sm leading-relaxed text-[var(--muted)]">{f.description}</p>
                  <ul className="mt-4 space-y-1.5 text-sm text-[var(--ink-dim)]">
                    {f.bullets.map((b) => (
                      <li key={b} className="flex items-start gap-2">
                        <span className="mt-[0.45em] size-1 shrink-0 rounded-full bg-[var(--accent)]" aria-hidden />
                        {b}
                      </li>
                    ))}
                  </ul>
                </HoverCard>
              </article>
            ))}
          </RevealStagger>
        </div>
      </section>

      <section className="container pt-20 pb-24" aria-label="Go deeper">
        <Reveal className="mx-auto flex max-w-3xl flex-col items-center gap-4 text-center">
          <p className="ledger inline-flex items-center gap-2 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
            <BookOpen className="size-3.5" aria-hidden />
            Under the hood
          </p>
          <h2 className="text-balance font-serif text-3xl font-semibold tracking-tight text-[var(--ink)]">
            The documentation covers every one of these features in depth.
          </h2>
          <p className="text-base leading-relaxed text-[var(--muted)]">
            Architecture, build instructions, supported models, performance, testing — the full technical
            documentation is published here, converted from the repository&apos;s own docs.
          </p>
          <div className="mt-2 flex flex-wrap justify-center gap-3">
            <Button asChild size="lg" variant="secondary">
              <Link href="/docs">
                <BookOpen />
                Browse the docs
              </Link>
            </Button>
            <Button asChild size="lg" variant="outline">
              <Link href="/docs/getting-started/first-run">
                First-run guide
                <ArrowRight className="size-4" />
              </Link>
            </Button>
            <Button asChild size="lg" variant="ghost" className="text-[var(--muted)]">
              <Link href="/downloads">
                <Download />
                Get the app
              </Link>
            </Button>
          </div>
        </Reveal>
      </section>

      <CtaBand />
    </>
  );
}