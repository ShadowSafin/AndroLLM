"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { useGSAP } from "@gsap/react";
import { ChevronDown } from "lucide-react";
import { cn } from "@/lib/utils";
import { motionDurations } from "@/lib/motion";

type Item = { id: string; title: string; body: ReactNode };

export function Accordion({ items, className, defaultOpen }: { items: Item[]; className?: string; defaultOpen?: string }) {
  const [open, setOpen] = useState<string | null>(defaultOpen ?? null);
  return (
    <div className={cn("divide-y divide-[var(--line)] rounded-card border border-[var(--line)] bg-[var(--surface)] shadow-card", className)}>
      {items.map((it) => (
        <AccordionItem key={it.id} item={it} open={open === it.id} onToggle={() => setOpen(open === it.id ? null : it.id)} />
      ))}
    </div>
  );
}

function AccordionItem({ item, open, onToggle }: { item: Item; open: boolean; onToggle: () => void }) {
  const contentRef = useRef<HTMLDivElement | null>(null);
  const innerRef = useRef<HTMLDivElement | null>(null);

  useGSAP(
    () => {
      const wrap = contentRef.current;
      const inner = innerRef.current;
      if (!wrap || !inner) return;
      const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      if (reduce) return;
      if (open) {
        gsap.fromTo(
          inner,
          { y: -8, opacity: 0 },
          { y: 0, opacity: 1, duration: motionDurations.small, ease: "power2.out" }
        );
      }
    },
    { scope: contentRef, dependencies: [open] }
  );

  return (
    <div>
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={open}
        className="group flex w-full items-center justify-between gap-4 px-5 py-4 text-left transition-colors hover:bg-[var(--mutedsurface)]"
      >
        <span className="font-serif text-base font-semibold text-[var(--ink)]">{item.title}</span>
        <span
          className="grid size-7 place-items-center rounded-full border border-[var(--line)] bg-[var(--surface)] text-[var(--muted)] transition-all duration-300 group-hover:border-[var(--accent)] group-hover:text-[var(--accent-deep)]"
          style={{ transform: open ? "rotate(180deg)" : "rotate(0deg)" }}
        >
          <ChevronDown className="size-3.5" aria-hidden />
        </span>
      </button>
      <div
        ref={contentRef}
        style={{
          display: "grid",
          gridTemplateRows: open ? "1fr" : "0fr",
          transition: `grid-template-rows ${motionDurations.small}s cubic-bezier(0.2, 1, 0.3, 1)`,
        }}
        aria-hidden={!open}
      >
        <div className="overflow-hidden">
          <div ref={innerRef} className="px-5 pb-5 text-sm leading-relaxed text-[var(--muted)]">
            {item.body}
          </div>
        </div>
      </div>
    </div>
  );
}

export function Tabs({
  tabs,
  defaultIndex = 0,
  className,
}: {
  tabs: { id: string; label: ReactNode; body: ReactNode }[];
  defaultIndex?: number;
  className?: string;
}) {
  const [active, setActive] = useState(defaultIndex);
  const refs = useRef<Array<HTMLButtonElement | null>>([]);
  const indicatorRef = useRef<HTMLSpanElement | null>(null);
  const wrapRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (typeof window === "undefined") return;
    const el = refs.current[active];
    const indicator = indicatorRef.current;
    if (!el || !indicator) return;
    const rect = el.getBoundingClientRect();
    const parentRect = el.parentElement!.getBoundingClientRect();
    indicator.style.transform = `translateX(${rect.left - parentRect.left}px)`;
    indicator.style.width = `${rect.width}px`;
  }, [active]);

  return (
    <div className={className}>
      <div ref={wrapRef} className="relative inline-flex flex-wrap gap-1 rounded-pill border border-[var(--line)] bg-[var(--surface)] p-1">
        <span
          ref={indicatorRef}
          className="absolute top-1 left-0 h-[calc(100%-0.5rem)] rounded-pill bg-[var(--accent)] transition-all duration-500 ease-out"
          style={{ width: 0 }}
        />
        {tabs.map((t, i) => (
          <button
            key={t.id}
            ref={(el) => {
              refs.current[i] = el;
            }}
            type="button"
            onClick={() => setActive(i)}
            className={cn(
              "relative z-10 rounded-pill px-4 py-2 text-sm font-semibold transition-colors",
              active === i ? "text-white" : "text-[var(--muted)] hover:text-[var(--ink)]"
            )}
            style={{ color: active === i ? "white" : undefined }}
          >
            {t.label}
          </button>
        ))}
      </div>
      <div className="mt-6">{tabs[active]?.body}</div>
    </div>
  );
}

export function HoverCard({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div
      className={cn(
        "card group relative overflow-hidden transition-all duration-500 hover:-translate-y-1 hover:shadow-[var(--shadow-ember-float-soft)]",
        className
      )}
    >
      {children}
      <span
        aria-hidden
        className="pointer-events-none absolute inset-0 opacity-0 transition-opacity duration-700 group-hover:opacity-100"
        style={{
          background:
            "radial-gradient(80% 50% at var(--mx,50%) var(--my,0%), color-mix(in srgb,var(--accent)_22%,transparent), transparent 60%)",
        }}
        onPointerMove={(e) => {
          const r = (e.currentTarget.parentElement as HTMLElement)?.getBoundingClientRect();
          if (!r) return;
          (e.currentTarget.parentElement as HTMLElement).style.setProperty("--mx", `${((e.clientX - r.left) / r.width) * 100}%`);
          (e.currentTarget.parentElement as HTMLElement).style.setProperty("--my", `${((e.clientY - r.top) / r.height) * 100}%`);
        }}
      />
    </div>
  );
}