"use client";

import { useEffect, useRef } from "react";
import { useInView } from "@/hooks/use-in-view";
import { prefersReducedMotion } from "@/lib/motion";

export function WordByWord({
  text,
  className,
  wordClassName,
  delay = 0,
  stagger = 0.05,
  threshold = 0.4,
  once = true,
}: {
  text: string;
  className?: string;
  wordClassName?: string;
  delay?: number;
  stagger?: number;
  threshold?: number;
  once?: boolean;
}) {
  const { ref, inView } = useInView<HTMLSpanElement>({ threshold, once });
  const words = text.split(" ");
  const ranRef = useRef(false);

  useEffect(() => {
    if (!inView) return;
    if (once && ranRef.current) return;
    if (once) ranRef.current = true;
    const node = ref.current;
    if (!node) return;
    const els = Array.from(node.querySelectorAll<HTMLElement>("[data-word]"));
    if (prefersReducedMotion()) {
      els.forEach((el) => el.classList.remove("opacity-0", "translate-y-2"));
      return;
    }
    els.forEach((el) => el.classList.add("opacity-0", "translate-y-2"));
    els.forEach((el, i) => {
      setTimeout(() => {
        el.style.transition = "transform 0.6s cubic-bezier(0.2, 1, 0.3, 1), opacity 0.6s ease-out";
        el.classList.remove("opacity-0", "translate-y-2");
      }, delay * 1000 + i * stagger * 1000);
    });
  }, [inView, once, delay, stagger, ref]);

  return (
    <span ref={ref} className={className} aria-label={text}>
      {words.map((w, i) => (
        <span key={i} data-word className={`inline-block ${wordClassName ?? ""}`}>
          {w}
          {i < words.length - 1 ? "\u00A0" : ""}
        </span>
      ))}
    </span>
  );
}