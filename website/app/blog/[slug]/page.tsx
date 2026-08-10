import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { ArrowLeft, Clock } from "lucide-react";
import { WordByWord } from "@/components/motion/word-by-word";
import { AnimatedUnderline } from "@/components/motion/animated-underline";
import { blogPosts } from "@/content/blog";

export const dynamicParams = false;

export function generateStaticParams() {
  return blogPosts.map((p) => ({ slug: p.slug }));
}

export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }): Promise<Metadata> {
  const { slug } = await params;
  const post = blogPosts.find((p) => p.slug === slug);
  if (!post) return {};
  return {
    title: `${post.title} — AndroLLM`,
    description: post.excerpt,
    alternates: { canonical: `/blog/${post.slug}` },
  };
}

export default async function BlogPostPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const post = blogPosts.find((p) => p.slug === slug);
  if (!post) notFound();

  return (
    <>
      <article className="container max-w-3xl py-24 md:py-32">
        <AnimatedUnderline>
          <Link
            href="/blog"
            className="inline-flex items-center gap-2 text-sm font-semibold text-[var(--faint)] transition-colors hover:text-[var(--accent-deep)] dark:hover:text-[var(--accent-soft)]"
          >
            <ArrowLeft className="size-3.5" aria-hidden /> All posts
          </Link>
        </AnimatedUnderline>

        <header className="mt-10">
          <p className="flex items-center gap-2 text-[11px] font-bold uppercase tracking-[0.15em] text-[var(--faint)]">
            <Clock className="size-3" aria-hidden />
            {post.date} · {post.readMin} min read
          </p>
          <h1 className="mt-4 font-serif text-3xl font-semibold leading-tight text-[var(--ink)] md:text-4xl">
            <WordByWord text={post.title} />
          </h1>
          <p className="mt-5 text-lg leading-relaxed text-[var(--muted)]">{post.excerpt}</p>
        </header>

        <div className="mt-12 border-t border-[var(--line)] pt-10">
          {post.body.map((block, i) => (
            <div key={i} className="mt-8 first:mt-0">
              {block.h && <h2 className="font-serif text-xl font-semibold text-[var(--ink)]">{block.h}</h2>}
              {block.p.map((para, j) => (
                <p key={j} className="mt-4 text-[15px] leading-[1.85] text-[var(--ink-dim)]">
                  {para}
                </p>
              ))}
            </div>
          ))}
        </div>
      </article>
    </>
  );
}