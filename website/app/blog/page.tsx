import type { Metadata } from "next";
import Link from "next/link";
import { ArrowRight, Clock } from "lucide-react";
import { SectionHeading } from "@/components/marketing/section-heading";
import { Reveal } from "@/animations/reveal";
import { ScrambleText } from "@/components/motion/scramble-text";
import { Magnetic } from "@/components/motion/magnetic";
import { blogPosts } from "@/content/blog";

export const metadata: Metadata = {
  title: "Blog — AndroLLM",
  description: "Notes from the AndroLLM project: launches, deep dives into the engine, memory, voice, and how the open-source website stays in sync with the repo.",
  alternates: { canonical: "/blog" },
};

export default function BlogIndexPage() {
  return (
    <>
      <section className="container py-28 md:py-36">
        <SectionHeading
          eyebrow="Blog"
          title={<ScrambleText text="Notes from the project." />}
          description="Technical writing derived from the repository itself — engine internals, memory design, voice, and release notes."
        />

        <div className="mx-auto mt-14 grid max-w-5xl gap-6 md:grid-cols-2">
          {blogPosts.map((post, i) => (
            <Reveal key={post.slug} delay={i * 0.07}>
              <Link
                href={`/blog/${post.slug}`}
                className="card group flex h-full flex-col p-7 transition-colors hover:border-[var(--accent)]"
              >
                <p className="flex items-center gap-2 text-[11px] font-bold uppercase tracking-[0.15em] text-[var(--faint)]">
                  <Clock className="size-3" aria-hidden />
                  {post.date} · {post.readMin} min
                </p>
                <h2 className="mt-4 font-serif text-xl font-semibold leading-snug text-[var(--ink)] transition-colors group-hover:text-[var(--accent-deep)] dark:group-hover:text-[var(--accent-soft)]">
                  {post.title}
                </h2>
                <p className="mt-3 flex-1 text-sm leading-relaxed text-[var(--muted)]">{post.excerpt}</p>
                <p className="mt-5 inline-flex items-center gap-2 text-sm font-bold text-[var(--accent-deep)] transition-transform duration-300 group-hover:translate-x-1 dark:text-[var(--accent-soft)]">
                  Read the post <ArrowRight className="size-3.5" aria-hidden />
                </p>
              </Link>
            </Reveal>
          ))}
        </div>

        <Reveal className="mx-auto mt-16 max-w-5xl">
          <div className="rounded-card border border-[var(--line)] bg-[var(--surface)] p-7 text-center shadow-card">
            <h2 className="font-serif text-xl font-semibold text-[var(--ink)]">Want project news instead?</h2>
            <p className="mx-auto mt-2 max-w-xl text-sm text-[var(--muted)]">
              The changelog tracks every release; the roadmap lists what&rsquo;s next. Both stay in sync with the main branch.
            </p>
            <div className="mt-5 flex flex-wrap justify-center gap-3">
              <Magnetic strength={0.18}>
                <Link href="/changelog" className="btn btn-primary">Changelog</Link>
              </Magnetic>
              <Magnetic strength={0.18}>
                <Link href="/roadmap" className="btn btn-ghost">Roadmap</Link>
              </Magnetic>
            </div>
          </div>
        </Reveal>
      </section>
    </>
  );
}