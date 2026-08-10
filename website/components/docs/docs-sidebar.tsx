"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { motion, AnimatePresence } from "framer-motion";
import { BookOpen, ChevronRight, Search, X } from "lucide-react";
import { docGroups } from "@/lib/docs";
import { cn } from "@/lib/utils";

export function DocsSidebar() {
  const pathname = usePathname();
  const [query, setQuery] = useState("");

  const results = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return null;
    const hits: { slug: string; title: string; group: string }[] = [];
    for (const g of docGroups) {
      for (const d of g.docs) {
        if (
          d.title.toLowerCase().includes(q) ||
          d.description.toLowerCase().includes(q) ||
          g.label.toLowerCase().includes(q)
        ) {
          hits.push({ slug: d.slug, title: d.title, group: g.label });
        }
      }
    }
    return hits;
  }, [query]);

  const groups = useMemo(() => {
    if (!results) return docGroups;
    const matched = new Set(results.map((r) => r.slug));
    return docGroups
      .map((g) => ({ ...g, docs: g.docs.filter((d) => matched.has(d.slug)) }))
      .filter((g) => g.docs.length > 0);
  }, [results]);

  return (
    <nav aria-label="Documentation sections" className="flex h-full flex-col">
      <div className="relative mb-5">
        <Search className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-[var(--faint)]" aria-hidden />
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search the docs…"
          className="w-full rounded-pill border border-[var(--line)] bg-[var(--mutedsurface)] py-2.5 pl-10 pr-9 text-sm text-[var(--ink)] outline-none transition-colors placeholder:text-[var(--faint)] focus:border-[var(--accent)]"
        />
        {query && (
          <button
            type="button"
            onClick={() => setQuery("")}
            aria-label="Clear search"
            className="absolute right-3 top-1/2 -translate-y-1/2 text-[var(--faint)] transition-colors hover:text-[var(--ink)]"
          >
            <X className="size-4" />
          </button>
        )}
      </div>

      <div className="flex-1 space-y-7 overflow-y-auto pr-1 scrollbar-none">
        {groups.map((g) => (
          <section key={g.id}>
            <h2 className="ledger mb-3 flex items-center gap-2 text-[var(--faint)]">
              <BookOpen className="size-3.5" aria-hidden />
              {g.label}
            </h2>
            <ul className="space-y-1">
              {g.docs.map((d) => {
                const active = pathname === `/docs/${d.slug}`;
                return (
                  <li key={d.slug}>
                    <Link
                      href={`/docs/${d.slug}`}
                      className={cn(
                        "group relative flex items-center justify-between gap-2 rounded-slip border border-transparent px-3 py-2 text-sm text-[var(--muted)] transition-all duration-300",
                        active
                          ? "border-[color-mix(in_srgb,var(--accent)_35%,transparent)] bg-[color-mix(in_srgb,var(--accent)_7%,transparent)] font-semibold text-[var(--ink)]"
                          : "hover:border-[var(--line)] hover:bg-[var(--mutedsurface)] hover:text-[var(--ink)]"
                      )}
                    >
                      {active && (
                        <motion.span
                          layoutId="docs-active-bar"
                          className="absolute left-0 top-1/2 h-5 w-0.5 -translate-y-1/2 rounded-full bg-[var(--accent)]"
                          transition={{ type: "spring", bounce: 0.3, duration: 0.6 }}
                        />
                      )}
                      <span className="truncate">{d.title}</span>
                      <AnimatePresence>
                        {active && (
                          <motion.span
                            initial={{ opacity: 0, x: -4 }}
                            animate={{ opacity: 1, x: 0 }}
                            exit={{ opacity: 0, x: -4 }}
                            transition={{ duration: 0.25 }}
                          >
                            <ChevronRight className="size-3.5 shrink-0 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]" />
                          </motion.span>
                        )}
                      </AnimatePresence>
                    </Link>
                  </li>
                );
              })}
            </ul>
          </section>
        ))}
        {results && results.length === 0 && (
          <p className="rounded-slip border border-[var(--line)] bg-[var(--mutedsurface)] px-4 py-3 text-sm text-[var(--muted)]">
            No documents match “{query}”.
          </p>
        )}
      </div>
    </nav>
  );
}