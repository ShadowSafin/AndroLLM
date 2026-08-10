"use client";

import { useEffect, useRef, useState } from "react";
import { useInView } from "@/hooks/use-in-view";
import { motionDistances, motionDurations, prefersReducedMotion } from "@/lib/motion";

type Props = {
  value: number;
  suffix?: string;
  prefix?: string;
  decimals?: number;
  duration?: number;
  format?: (n: number) => string;
  className?: string;
};

const easeOutCubic = (t: number) => 1 - Math.pow(1 - t, 3);

export function AnimatedCounter({
  value,
  suffix = "",
  prefix = "",
  decimals = 0,
  duration = motionDurations.epic,
  format,
  className,
}: Props) {
  const { ref, inView } = useInView<HTMLSpanElement>({ threshold: 0.4, once: true });
  const [display, setDisplay] = useState(() => format ? format(0) : (0).toFixed(decimals));
  const startedRef = useRef(false);
  const rafRef = useRef<number | null>(null);

  useEffect(() => {
    if (!inView || startedRef.current) return;
    startedRef.current = true;

    if (prefersReducedMotion()) {
      setDisplay(format ? format(value) : value.toFixed(decimals));
      return;
    }

    const start = performance.now();
    const tick = (now: number) => {
      const elapsed = Math.min(1, (now - start) / (duration * 1000));
      const eased = easeOutCubic(elapsed);
      const current = value * eased;
      setDisplay(format ? format(current) : current.toFixed(decimals));
      if (elapsed < 1) rafRef.current = requestAnimationFrame(tick);
      else setDisplay(format ? format(value) : value.toFixed(decimals));
    };
    rafRef.current = requestAnimationFrame(tick);

    return () => {
      if (rafRef.current) cancelAnimationFrame(rafRef.current);
    };
  }, [inView, value, duration, decimals, format]);

  return (
    <span ref={ref} className={className} data-counter>
      {prefix}
      {display}
      {suffix}
    </span>
  );
}

export function StatBlock({
  value,
  label,
  note,
  suffix = "",
  prefix = "",
  decimals = 0,
  format,
  className,
}: {
  value: number;
  label: string;
  note?: string;
  suffix?: string;
  prefix?: string;
  decimals?: number;
  format?: (n: number) => string;
  className?: string;
}) {
  return (
    <div
      className={`card relative overflow-hidden p-6 transition-transform duration-500 hover:-translate-y-1 hover:shadow-[0_18px_40px_-18px_rgba(217,119,87,0.45)] ${className ?? ""}`}
      data-stat
    >
      <div className="absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-[var(--accent)] to-transparent opacity-60" />
      <AnimatedCounter
        value={value}
        suffix={suffix}
        prefix={prefix}
        decimals={decimals}
        format={format}
        className="block font-mono text-3xl font-bold text-[var(--ink)] md:text-4xl"
      />
      <p className="mt-3 text-sm font-medium text-[var(--ink-dim)]">{label}</p>
      {note ? <p className="mt-1 text-xs leading-relaxed text-[var(--faint)]">{note}</p> : null}
      <span
        aria-hidden
        className="absolute -right-6 -top-6 size-20 rounded-full bg-[var(--accent-soft)] opacity-40 blur-2xl transition-opacity duration-500 group-hover:opacity-70"
      />
    </div>
  );
}