import type { Metadata } from "next";
import Link from "next/link";
import { ArrowRight, BookOpen } from "lucide-react";
import { SectionHeading } from "@/components/marketing/section-heading";
import { Reveal } from "@/animations/reveal";
import { docGroups } from "@/lib/docs";

export const metadata: Metadata = {
  title: "Documentation — AndroLLM",
  description:
    "The complete AndroLLM documentation: architecture, AI engine, voice assistant, agent platform, cloud providers, memory, building from source, and more.",
  alternates: { canonical: "/docs" },
};

export default function DocsIndex() {
  return (
    <section className="container py-28 md:py-36">
      <SectionHeading
        eyebrow="Documentation"
        title="Every subsystem, documented."
        description="The full technical documentation — converted from the repository's own docs, so what you read here is exactly what the codebase says. No marketing layer."
      />

      <div className="mt-16 grid gap-5 md:grid-cols-2 lg:grid-cols-3">
        {docGroups.map((g, i) => (
          <Reveal key={g.id} delay={i * 0.05} className="h-full">
            <div className="card card-hover flex h-full flex-col p-6">
              <span className="flex size-11 items-center justify-center rounded-card border border-[color-mix(in_srgb,var(--accent)_30%,var(--line))] bg-[color-mix(in_srgb,var(--accent)_8%,var(--surface))] text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
                <BookOpen className="size-5" aria-hidden />
              </span>
              <h2 className="mt-5 font-serif text-xl font-semibold tracking-tight text-[var(--ink)]">{g.label}</h2>
              <p className="mt-1 text-sm text-[var(--muted)]">{g.blurb}</p>
              <ul className="mt-5 space-y-2">
                {g.docs.map((d) => (
                  <li key={d.slug}>
                    <Link
                      href={`/docs/${d.slug}`}
                      className="group flex items-center justify-between gap-3 text-sm text-[var(--ink-dim)] transition-colors hover:text-[var(--accent-deep)] dark:hover:text-[var(--accent-soft)]"
                    >
                      <span className="truncate">{d.title}</span>
                      <span className="shrink-0 text-[var(--faint)] transition-colors group-hover:text-[var(--accent)]">
                        <ArrowRight className="size-3.5" />
                      </span>
                    </Link>
                    <p className="mt-0.5 text-xs leading-relaxed text-[var(--faint)]">{d.description}</p>
                  </li>
                ))}
              </ul>
            </div>
          </Reveal>
        ))}
      </div>
    </section>
  );
}