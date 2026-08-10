import type { Metadata } from "next";
import Link from "next/link";
import { Scale, FileCheck, Recycle } from "lucide-react";
import { SectionHeading } from "@/components/marketing/section-heading";
import { Reveal } from "@/animations/reveal";
import { WordByWord } from "@/components/motion/word-by-word";
import { CursorGlow } from "@/components/motion/cursor-glow";
import { HoverCard } from "@/components/motion/accordion";
import { AnimatedUnderline } from "@/components/motion/animated-underline";
import { site } from "@/lib/site";

export const metadata: Metadata = {
  title: "License — AndroLLM",
  description:
    "AndroLLM is licensed under the Apache License 2.0 — free to use, modify, and redistribute, with attribution and no warranty.",
  alternates: { canonical: "/license" },
};

const permissions = ["Commercial use", "Modification", "Distribution", "Patent protection", "Private use"];
const conditions = ["License & copyright notice", "State of changes", "Same license for source-only repositories"];
const limitations = ["Trademark use", "Liability", "Warranty"];

export default function LicensePage() {
  return (
    <>
      <section className="container py-28 md:py-36">
        <CursorGlow size={460} intensity={0.22} color="var(--accent)">
          <SectionHeading
            eyebrow="License"
            title={<WordByWord text="Open source, Apache 2.0." />}
            description="AndroLLM is free software. Use it, modify it, ship it — with attribution, and without warranty from us."
          />
        </CursorGlow>

        <Reveal className="mx-auto mt-14 max-w-3xl">
          <div className="card flex items-start gap-4 p-6">
            <Scale className="mt-0.5 size-4 shrink-0 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]" aria-hidden />
            <div className="text-sm leading-relaxed text-[var(--muted)]">
              <p>
                The entire repository — app, engine, documentation, and this website — is released under the{" "}
                <strong className="font-semibold text-[var(--ink)]">Apache License, Version 2.0</strong> (January 2004).
                The full license text is published by the Apache Software Foundation at{" "}
                <AnimatedUnderline>
                  <Link
                    href="https://www.apache.org/licenses/LICENSE-2.0"
                    target="_blank"
                    rel="noreferrer"
                    className="font-serif text-[15px] font-medium text-[var(--accent-deep)] dark:text-[var(--accent-soft)]"
                  >
                    apache.org/licenses/LICENSE-2.0
                  </Link>
                </AnimatedUnderline>
                , and a copy is committed in the repository at{" "}
                <AnimatedUnderline>
                  <Link
                    href={`${site.repo}/blob/main/LICENSE.md`}
                    target="_blank"
                    rel="noreferrer"
                    className="font-mono text-[12px] font-medium text-[var(--accent-deep)] dark:text-[var(--accent-soft)]"
                  >
                    LICENSE.md
                  </Link>
                </AnimatedUnderline>
                .
              </p>
              <p className="mt-3">
                Not all code is ours: models come under their own licenses (see{" "}
                <AnimatedUnderline>
                  <Link
                    href={`${site.repo}/blob/main/LICENSES.md`}
                    target="_blank"
                    rel="noreferrer"
                    className="font-mono text-[12px] font-medium text-[var(--accent-deep)] dark:text-[var(--accent-soft)]"
                  >
                    LICENSES.md
                  </Link>
                </AnimatedUnderline>
                ), and bundled ONNX voice models have their own terms where applicable.
              </p>
            </div>
          </div>
        </Reveal>

        <div className="mt-14 grid gap-6 lg:grid-cols-3">
          {[
            { icon: FileCheck, title: "You can", items: permissions, tone: "ok" },
            { icon: Recycle, title: "You must", items: conditions, tone: "warn" },
            { icon: Scale, title: "You cannot", items: limitations, tone: "danger" },
          ].map((col, i) => (
            <Reveal key={col.title} delay={i * 0.07}>
              <HoverCard className="h-full p-6">
                <div className="flex items-center gap-2.5">
                  <col.icon className="size-4 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]" aria-hidden />
                  <h3 className="font-serif text-lg font-semibold text-[var(--ink)]">{col.title}</h3>
                </div>
                <ul className="mt-5 space-y-2.5">
                  {col.items.map((item) => (
                    <li key={item} className="flex items-start gap-2.5 text-sm leading-relaxed text-[var(--ink-dim)]">
                      <span className={`mt-[0.55em] size-1.5 shrink-0 rounded-full bg-[var(--${col.tone})]`} aria-hidden />
                      {item}
                    </li>
                  ))}
                </ul>
              </HoverCard>
            </Reveal>
          ))}
        </div>

        <Reveal className="mx-auto mt-14 max-w-3xl">
          <div className="rounded-card border border-[var(--line)] bg-[var(--surface)] p-6 shadow-card">
            <p className="text-sm font-semibold text-[var(--ink)]">Disclaimer</p>
            <p className="mt-2 text-sm leading-relaxed text-[var(--muted)]">
              Licensed under the Apache License, Version 2.0 (the &ldquo;License&rdquo;); the software is provided on
              an &ldquo;AS IS&rdquo; basis, without warranties or conditions of any kind, either express or implied.
              You may obtain a copy of the License at apache.org/licenses/LICENSE-2.0. Unless required by applicable law
              or agreed to in writing, software distributed under the License is distributed on an &ldquo;AS IS&rdquo;
              basis, without warranties or conditions of any kind, either express or implied.
            </p>
          </div>
        </Reveal>
      </section>
    </>
  );
}