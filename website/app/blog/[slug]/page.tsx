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
          <p className="flex items-center gap-2 font-geist text-[11px] font-bold uppercase tracking-tight text-[var(--faint)]">
            <Clock className="size-3" aria-hidden />
            {post.date} · {post.readMin} min read
          </p>
          <h1 className="mt-4 text-balance bg-gradient-to-br from-black from-30% to-black/40 bg-clip-text py-1 font-geist text-3xl font-semibold leading-none tracking-tighter text-transparent sm:text-4xl md:text-5xl dark:from-white dark:to-white/40">
            <WordByWord text={post.title} />
          </h1>
          <p className="mt-5 font-geist text-lg tracking-tight leading-relaxed text-gray-600 dark:text-gray-400 md:text-xl">{post.excerpt}</p>
        </header>

        <div className="mt-12 border-t border-[var(--line)] pt-10">
          {post.body.map((block, i) => (
            <div key={i} className="mt-8 first:mt-0">
              {block.h && <h2 className="font-geist text-balance text-xl font-semibold tracking-tight leading-tight text-[var(--ink)]">{block.h}</h2>}
              {block.p.map((para, j) => (
                <p key={j} className="mt-4 font-geist text-[15px] tracking-tight leading-[1.85] text-gray-600 dark:text-gray-400">
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