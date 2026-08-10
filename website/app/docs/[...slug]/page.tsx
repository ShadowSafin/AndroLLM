import type { Metadata } from "next";
import path from "path";
import fs from "fs";
import { notFound } from "next/navigation";
import { compileMDX } from "next-mdx-remote/rsc";
import remarkGfm from "remark-gfm";
import { DocsSidebar } from "@/components/docs/docs-sidebar";
import { PrevNext } from "@/components/docs/prev-next";
import { mdxComponents } from "@/components/docs/mdx-components";
import { allDocs, findDoc, titleLine } from "@/lib/docs";
import { escapeAngleBrackets } from "@/lib/escape-angles";

const contentDir = path.join(process.cwd(), "content", "docs");

function readDoc(slug: string): string {
  try {
    return fs.readFileSync(path.join(contentDir, `${slug}.md`), "utf8");
  } catch {
    return "";
  }
}

export function generateStaticParams() {
  return allDocs.map((d) => ({ slug: d.slug.split("/") }));
}

export async function generateMetadata({ params }: { params: Promise<{ slug: string[] }> }): Promise<Metadata> {
  const { slug: segments } = await params;
  const slug = segments.join("/");
  const hit = findDoc(slug);
  if (!hit) return { title: "Documentation — AndroLLM" };
  return {
    title: `${hit.entry.title} — AndroLLM Docs`,
    description: hit.entry.description,
    alternates: { canonical: `/docs/${slug}` },
  };
}

export default async function DocPage({ params }: { params: Promise<{ slug: string[] }> }) {
  const { slug: segments } = await params;
  const slug = segments.join("/");
  const hit = findDoc(slug);
  if (!hit) return notFound();

  const raw = readDoc(slug);
  if (!raw) return notFound();

  const source = escapeAngleBrackets(raw.replace(/^#\s+.+?$/m, "")).trimStart();

  const { content } = await compileMDX({
    source,
    options: { mdxOptions: { remarkPlugins: [remarkGfm] } },
    components: mdxComponents,
  });

  const sourceTitle = titleLine(raw) ?? hit.entry.title;

  return (
    <div className="container py-12 md:py-16">
      <div className="grid gap-10 lg:grid-cols-[240px_minmax(0,1fr)] lg:gap-14">
        <aside className="hidden lg:block">
          <div className="sticky top-28 h-[calc(100vh-7rem)]">
            <DocsSidebar />
          </div>
        </aside>

        <details className="mb-8 rounded-card border border-[var(--line)] bg-[var(--deep)] p-4 lg:hidden">
          <summary className="cursor-pointer font-serif text-sm font-semibold text-[var(--ink)]">
            Jump to a document
          </summary>
          <div className="mt-4">
            <DocsSidebar />
          </div>
        </details>

        <article className="min-w-0 max-w-3xl">
          <header className="border-b border-[var(--line)] pb-8">
            <p className="ledger inline-flex items-center gap-2 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
              <span className="inline-block size-1.5 rounded-full bg-[var(--accent)]" aria-hidden />
              {hit.group.label}
            </p>
            <h1 className="mt-3 font-serif text-4xl font-semibold leading-tight tracking-tight text-[var(--ink)] sm:text-5xl">
              {sourceTitle}
            </h1>
            <p className="mt-4 max-w-2xl text-base leading-relaxed text-[var(--muted)]">{hit.entry.description}</p>
          </header>

          <div className="prose-ledger mt-4">{content}</div>

          <PrevNext slug={slug} />
        </article>
      </div>
    </div>
  );
}