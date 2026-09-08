"use client";
import { useEffect, useRef } from "react";

// Simple scroll-reveal that plays well with SmoothScroll.
// Uses IntersectionObserver + RAF; respects prefers-reduced-motion.
// Variants: subtle y + opacity + optional scale — tuned for the black ledger.

type Variant = "rise" | "scale" | "fade";

export function ScrollReveal({
  children,
  className,
  delay = 0,
  variant = "rise",
  as: Tag = "div",
}: {
  children: React.ReactNode;
  className?: string;
  delay?: number;
  variant?: Variant;
  as?: "div" | "section" | "article" | "li" | "header";
}) {
  const ref = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (reduce) {
      el.style.opacity = "1";
      el.style.transform = "none";
      return;
    }
    el.style.opacity = "0";
    const base =
      variant === "scale"
        ? "translateY(14px) scale(0.985)"
        : variant === "fade"
          ? "translateY(6px)"
          : "translateY(18px)";
    el.style.transform = base;
    el.style.willChange = "transform, opacity";
    el.style.transition = `opacity 0.7s cubic-bezier(0.22,1,0.36,1) ${delay}s, transform 0.75s cubic-bezier(0.22,1,0.36,1) ${delay}s`;

    const io = new IntersectionObserver(
      (entries) => {
        for (const e of entries) if (e.isIntersecting) {
          requestAnimationFrame(() => {
            el.style.opacity = "1";
            el.style.transform = "translateY(0) scale(1)";
          });
          io.disconnect();
        }
      },
      { threshold: 0.14, rootMargin: "0px 0px -6% 0px" }
    );
    io.observe(el);
    return () => io.disconnect();
  }, [delay, variant]);
  const T = Tag as any;
  return <T ref={ref as any} className={className}>{children}</T>;
}

export function ScrollStagger({
  children,
  className,
  stagger = 0.07,
  baseDelay = 0,
}: {
  children: React.ReactNode[];
  className?: string;
  stagger?: number;
  baseDelay?: number;
}) {
  return (
    <div className={className}>
      {children.map((c, i) => (
        <ScrollReveal key={i} delay={baseDelay + i * stagger}>{c}</ScrollReveal>
      ))}
    </div>
  );
}
