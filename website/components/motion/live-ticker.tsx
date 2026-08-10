"use client";

import { useEffect, useRef, useState } from "react";

const CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#@!$%&*+=";

export function CodeStream({ className, lines }: { className?: string; lines: string[] }) {
  const [text, setText] = useState("");
  const idxRef = useRef(0);

  useEffect(() => {
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (reduce) {
      setText(lines[0] ?? "");
      return;
    }
    let raf: number;
    let cancelled = false;
    const target = lines.join("\n");
    const run = () => {
      if (cancelled) return;
      idxRef.current = Math.min(target.length, idxRef.current + 4);
      const revealed = target.slice(0, idxRef.current);
      const rest = target
        .slice(idxRef.current, idxRef.current + 12)
        .split("")
        .map((c) => (c === "\n" ? "\n" : CHARS[Math.floor(Math.random() * CHARS.length)]))
        .join("");
      setText(revealed + rest);
      if (idxRef.current < target.length) {
        raf = requestAnimationFrame(run);
      } else {
        setText(target);
      }
    };
    raf = requestAnimationFrame(run);
    return () => {
      cancelled = true;
      cancelAnimationFrame(raf);
    };
  }, [lines]);

  return (
    <pre className={className} aria-live="off">
      <code>{text}</code>
      <span
        aria-hidden
        className="inline-block h-4 w-2 translate-y-1 bg-[var(--accent)] align-middle"
        style={{ animation: "blink 1s steps(2) infinite" }}
      />
      <style>{`@keyframes blink { 50% { opacity: 0; } }`}</style>
    </pre>
  );
}

export function StatusTicker({ items, className }: { items: string[]; className?: string }) {
  const [idx, setIdx] = useState(0);
  useEffect(() => {
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (reduce) return;
    const t = setInterval(() => setIdx((i) => (i + 1) % items.length), 2800);
    return () => clearInterval(t);
  }, [items.length]);
  return (
    <div className={className}>
      {items.map((it, i) => (
        <div
          key={i}
          className="absolute inset-0 flex items-center gap-2 transition-all duration-700 ease-out"
          style={{
            opacity: i === idx ? 1 : 0,
            transform: i === idx ? "translateY(0)" : "translateY(8px)",
          }}
        >
          <span className="size-1.5 animate-pulse rounded-full bg-[var(--ok)]" />
          <span className="font-mono text-[12px] text-[var(--muted)]">{it}</span>
        </div>
      ))}
    </div>
  );
}

export function LiveDot({ color = "var(--ok)" }: { color?: string }) {
  return (
    <span className="relative inline-flex size-2">
      <span
        className="absolute inline-flex size-full animate-ping rounded-full opacity-75"
        style={{ background: color }}
      />
      <span
        className="relative inline-flex size-2 rounded-full"
        style={{ background: color }}
      />
    </span>
  );
}