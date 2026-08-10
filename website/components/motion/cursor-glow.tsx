"use client";

import { useEffect, useRef, type ReactNode } from "react";

export function CursorGlow({
  children,
  className,
  size = 520,
  intensity = 0.35,
  color = "var(--accent)",
}: {
  children?: ReactNode;
  className?: string;
  size?: number;
  intensity?: number;
  color?: string;
}) {
  const rootRef = useRef<HTMLDivElement | null>(null);
  const glowRef = useRef<HTMLDivElement | null>(null);
  const rafRef = useRef<number | null>(null);
  const targetRef = useRef({ x: 0, y: 0 });
  const currentRef = useRef({ x: 0, y: 0 });

  useEffect(() => {
    const root = rootRef.current;
    const glow = glowRef.current;
    if (!root || !glow) return;

    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (reduce || window.matchMedia("(pointer: coarse)").matches) {
      glow.style.display = "none";
      return;
    }

    const onMove = (e: PointerEvent) => {
      const rect = root.getBoundingClientRect();
      targetRef.current.x = e.clientX - rect.left;
      targetRef.current.y = e.clientY - rect.top;
    };

    const tick = () => {
      const dx = targetRef.current.x - currentRef.current.x;
      const dy = targetRef.current.y - currentRef.current.y;
      currentRef.current.x += dx * 0.18;
      currentRef.current.y += dy * 0.18;
      glow.style.transform = `translate3d(${currentRef.current.x - size / 2}px, ${currentRef.current.y - size / 2}px, 0)`;
      rafRef.current = requestAnimationFrame(tick);
    };

    root.addEventListener("pointermove", onMove);
    rafRef.current = requestAnimationFrame(tick);

    return () => {
      root.removeEventListener("pointermove", onMove);
      if (rafRef.current) cancelAnimationFrame(rafRef.current);
    };
  }, [size]);

  return (
    <div ref={rootRef} className={`relative isolate ${className ?? ""}`}>
      <div
        ref={glowRef}
        aria-hidden
        className="pointer-events-none absolute left-0 top-0 will-change-transform"
        style={{
          width: size,
          height: size,
          background: `radial-gradient(circle at center, ${color} 0%, transparent 65%)`,
          opacity: intensity,
          filter: "blur(40px)",
          mixBlendMode: "screen",
        }}
      />
      <div className="relative">{children}</div>
    </div>
  );
}