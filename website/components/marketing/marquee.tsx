"use client";

import { motion, useReducedMotion } from "framer-motion";
import { cn } from "@/lib/utils";

export function Marquee({ items, className }: { items: string[]; className?: string }) {
  const reduce = useReducedMotion();

  return (
    <div className={cn("mask-fade-x relative mt-6 overflow-hidden", className)} role="presentation" aria-label="Technologies used by AndroLLM">
      <div className={cn("flex w-max items-center gap-10", !reduce && "animate-marquee")}>
        {[0, 1].map((dup) => (
          <div key={dup} className="flex items-center gap-10" aria-hidden={dup === 1}>
            {items.map((item) => (
              <span
                key={`${dup}-${item}`}
                className="whitespace-nowrap font-mono text-xs uppercase tracking-[0.18em] text-[var(--faint)] transition-colors hover:text-[var(--accent-deep)] dark:hover:text-[var(--accent-soft)]"
              >
                {item}
              </span>
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}