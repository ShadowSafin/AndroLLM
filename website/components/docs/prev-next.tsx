import Link from "next/link";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { findNeighbors } from "@/lib/docs";

export function PrevNext({ slug }: { slug: string }) {
  const { prev, next } = findNeighbors(slug);
  return (
    <nav aria-label="Document navigation" className="mt-12 grid gap-4 border-t border-[var(--line)] pt-8 sm:grid-cols-2">
      {prev ? (
        <Link
          href={`/docs/${prev.slug}`}
          className="group flex items-start gap-3 rounded-card border border-[var(--line)] bg-[var(--surface)] p-5 transition-all hover:border-[var(--accent)] hover:shadow-card"
        >
          <ChevronLeft className="mt-0.5 size-5 shrink-0 text-[var(--accent-deep)] transition-transform duration-300 group-hover:-translate-x-1 dark:text-[var(--accent-soft)]" />
          <span>
            <span className="block font-geist text-[10px] font-bold uppercase tracking-tight text-[var(--faint)]">Previous</span>
            <span className="mt-1 block font-geist text-base font-semibold tracking-tight leading-tight text-[var(--ink)]">{prev.title}</span>
            <span className="mt-0.5 block font-geist text-xs tracking-tight text-gray-600 dark:text-gray-400">{prev.description}</span>
          </span>
        </Link>
      ) : (
        <span className="hidden sm:block" />
      )}
      {next ? (
        <Link
          href={`/docs/${next.slug}`}
          className="group flex items-start justify-end gap-3 rounded-card border border-[var(--line)] bg-[var(--surface)] p-5 text-right transition-all hover:border-[var(--accent)] hover:shadow-card"
        >
          <span>
            <span className="block font-geist text-[10px] font-bold uppercase tracking-tight text-[var(--faint)]">Next</span>
            <span className="mt-1 block font-geist text-base font-semibold tracking-tight leading-tight text-[var(--ink)]">{next.title}</span>
            <span className="mt-0.5 block font-geist text-xs tracking-tight text-gray-600 dark:text-gray-400">{next.description}</span>
          </span>
          <ChevronRight className="mt-0.5 size-5 shrink-0 text-[var(--accent-deep)] transition-transform duration-300 group-hover:translate-x-1 dark:text-[var(--accent-soft)]" />
        </Link>
      ) : (
        <span className="hidden sm:block" />
      )}
    </nav>
  );
}