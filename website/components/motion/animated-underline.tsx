"use client";

import { useRef, type ReactNode } from "react";
import { gsap } from "gsap";
import { useGSAP } from "@gsap/react";

export function AnimatedUnderline({ children, className, color = "var(--accent)" }: { children: ReactNode; className?: string; color?: string }) {
  const ref = useRef<HTMLSpanElement | null>(null);
  useGSAP(
    () => {
      const root = ref.current;
      if (!root) return;
      const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      if (reduce) return;
      const line = root.querySelector<HTMLElement>("[data-underline]");
      if (!line) return;

      const xTo = gsap.quickTo(line, "scaleX", { duration: 0.5, ease: "power3.out" });
      const xTo2 = gsap.quickTo(line, "x", { duration: 0.5, ease: "power3.out" });

      const enter = () => {
        xTo(1);
        xTo2(0);
      };
      const leave = () => {
        xTo(0);
        xTo2(0);
      };
      const focus = () => {
        xTo(1);
        xTo2(0);
      };

      root.addEventListener("pointerenter", enter);
      root.addEventListener("pointerleave", leave);
      root.addEventListener("focusin", focus);
      root.addEventListener("focusout", leave);
      return () => {
        root.removeEventListener("pointerenter", enter);
        root.removeEventListener("pointerleave", leave);
        root.removeEventListener("focusin", focus);
        root.removeEventListener("focusout", leave);
      };
    },
    { scope: ref }
  );

  return (
    <span ref={ref} className={`relative inline-block ${className ?? ""}`}>
      <span>{children}</span>
      <span
        data-underline
        aria-hidden
        className="pointer-events-none absolute inset-x-0 -bottom-1 h-px origin-left scale-x-0"
        style={{ background: `linear-gradient(90deg, transparent, ${color} 30%, ${color} 70%, transparent)` }}
      />
    </span>
  );
}