"use client";

import { useRef, type ReactNode } from "react";
import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { useGSAP } from "@gsap/react";
import { cn } from "@/lib/utils";
import { easings } from "@/lib/motion";

gsap.registerPlugin(ScrollTrigger);

export function SectionShell({
  children,
  className,
  id,
}: {
  children: ReactNode;
  className?: string;
  id?: string;
}) {
  const ref = useRef<HTMLElement | null>(null);

  useGSAP(
    () => {
      const root = ref.current;
      if (!root) return;
      const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      if (reduce) return;
      ScrollTrigger.create({
        trigger: root,
        start: "top 90%",
        once: true,
        onEnter: () => {
          gsap.fromTo(
            root.querySelectorAll("[data-sec-line]"),
            { scaleX: 0, opacity: 0 },
            { scaleX: 1, opacity: 1, duration: 0.9, ease: easings.silky, stagger: 0.1 }
          );
        },
      });
    },
    { scope: ref }
  );

  return (
    <section ref={ref} id={id} className={cn("relative overflow-hidden", className)}>
      <span
        data-sec-line
        aria-hidden
        className="pointer-events-none absolute inset-x-0 top-0 h-px origin-left"
        style={{ background: "linear-gradient(90deg, transparent, var(--line) 20%, var(--line) 80%, transparent)" }}
      />
      {children}
    </section>
  );
}

export function TickerTape({
  items,
  className,
  speed = 40,
}: {
  items: string[];
  className?: string;
  speed?: number;
}) {
  const row = items.concat(items);
  return (
    <div className={cn("relative overflow-hidden", className)} aria-hidden>
      <div
        className="flex min-w-max items-center gap-8 whitespace-nowrap"
        style={{ animation: `ticker ${speed}s linear infinite` }}
      >
        {row.map((item, i) => (
          <span key={i} className="flex items-center gap-8 font-mono text-[11px] uppercase tracking-[0.18em] text-[var(--faint)]">
            {item}
            <span className="size-1 rotate-45 bg-[var(--accent)]" />
          </span>
        ))}
      </div>
      <style>{`@keyframes ticker { from { transform: translateX(0); } to { transform: translateX(-50%); } }`}</style>
    </div>
  );
}