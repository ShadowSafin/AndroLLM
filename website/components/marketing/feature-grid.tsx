import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { SectionHeading } from "@/components/marketing/section-heading";
import { Stagger, StaggerItem } from "@/animations/reveal";
import { pillars, type Feature } from "@/lib/features";
import { cn } from "@/lib/utils";

export function FeatureGrid({ features = pillars, detailed = true }: { features?: Feature[]; detailed?: boolean }) {
  return (
    <section className="py-24 sm:py-32" aria-label="Features">
      <div className="container">
        <SectionHeading
          eyebrow="Everything inside the app"
          title="One app. Nine engines of capability."
          description="Every feature below ships in the Android app today — verified against the source, not a design doc."
        />
        <Stagger className="mt-14 grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {features.map((f) => (
            <StaggerItem key={f.id}>
              <article
                id={f.id}
                className="card card-hover group flex h-full flex-col p-6"
              >
                <div className="flex items-center justify-between">
                  <span className="flex size-11 items-center justify-center rounded-card border border-[color-mix(in_srgb,var(--accent)_30%,var(--line))] bg-[var(--feature-icon-bg)] text-[var(--accent-deep)] transition-all duration-300 group-hover:bg-[var(--accent)] group-hover:text-[var(--btn-primary-text)] dark:text-[var(--accent-soft)]">
                    <f.icon className="size-5" aria-hidden />
                  </span>
                  {f.stat && (
                    <span className="ledger text-right text-[var(--faint)]">
                      <span className={cn("block text-sm not-italic tracking-normal text-[var(--accent-deep)] dark:text-[var(--accent-soft)]")}>
                        {f.stat.value}
                      </span>
                      {f.stat.value ? "fact" : ""}
                    </span>
                  )}
                </div>
                <h3 className="mt-5 font-serif text-xl font-semibold tracking-tight text-[var(--ink)]">{f.name}</h3>
                <p className="mt-1 text-sm text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">{f.tagline}</p>
                <p className="mt-3 text-sm leading-relaxed text-[var(--muted)]">{f.description}</p>
                <ul className="mt-4 space-y-1.5 text-sm text-[var(--ink-dim)]">
                  {f.bullets.slice(0, detailed ? f.bullets.length : 3).map((b) => (
                    <li key={b} className="flex items-start gap-2">
                      <span className="mt-[0.45em] size-1 shrink-0 rounded-full bg-[var(--accent)]" aria-hidden />
                      {b}
                    </li>
                  ))}
                </ul>
                {detailed && f.stat && (
                  <p className="mt-auto pt-4">
                    <span className="block rounded-slip border border-[var(--line-soft)] bg-[var(--deep)] px-3 py-2 font-mono text-[10px] leading-relaxed text-[var(--muted)]">
                      {f.stat.value} — {f.stat.label}
                    </span>
                  </p>
                )}
              </article>
            </StaggerItem>
          ))}
        </Stagger>
        <div className="mt-12 text-center">
          <Link
            href="/features"
            className="group inline-flex items-center gap-2 text-sm font-semibold text-[var(--accent-deep)] transition-colors hover:text-[var(--ink)] dark:text-[var(--accent-soft)]"
          >
            Explore every feature in detail
            <ArrowRight className="size-4 transition-transform group-hover:translate-x-1" />
          </Link>
        </div>
      </div>
    </section>
  );
}