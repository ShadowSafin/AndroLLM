"use client";

import { useEffect, useRef, useState } from "react";
import { motionDurations, prefersReducedMotion } from "@/lib/motion";

const SCRAMBLE = "!<>-_\\/[]{}—=+*^?#________";

export function ScrambleText({
  text,
  className,
  delay = 0,
  duration = motionDurations.large,
  trigger = "view",
}: {
  text: string;
  className?: string;
  delay?: number;
  duration?: number;
  trigger?: "view" | "mount" | "hover";
}) {
  const ref = useRef<HTMLSpanElement | null>(null);
  const [displayed, setDisplayed] = useState(text);
  const startedRef = useRef(false);

  useEffect(() => {
    if (trigger !== "view") return;
    const node = ref.current;
    if (!node) return;

    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting && !startedRef.current) {
          startedRef.current = true;
          startScramble(text, delay, duration, setDisplayed);
        }
      },
      { threshold: 0.5 }
    );
    observer.observe(node);
    return () => observer.disconnect();
  }, [text, delay, duration, trigger]);

  useEffect(() => {
    if (trigger === "mount") {
      startScramble(text, delay, duration, setDisplayed);
    }
  }, [text, delay, duration, trigger]);

  const onEnter = () => {
    if (trigger === "hover" && !startedRef.current) {
      startedRef.current = true;
      startScramble(text, 0, duration, setDisplayed);
    }
  };

  return (
    <span ref={ref} className={className} onPointerEnter={onEnter} data-scramble>
      {displayed}
    </span>
  );
}

function startScramble(text: string, delay: number, duration: number, set: (s: string) => void) {
  if (prefersReducedMotion()) {
    set(text);
    return;
  }
  const start = performance.now() + delay * 1000;
  const total = duration * 1000;
  const length = text.length;
  let raf: number | null = null;

  const tick = (now: number) => {
    const t = Math.min(1, (now - start) / total);
    if (t < 0) {
      raf = requestAnimationFrame(tick);
      return;
    }
    const revealed = Math.floor(t * length);
    let out = "";
    for (let i = 0; i < length; i++) {
      const ch = text[i];
      if (i < revealed) {
        out += ch === " " ? " " : ch;
      } else if (ch === " ") {
        out += " ";
      } else {
        out += SCRAMBLE[Math.floor(Math.random() * SCRAMBLE.length)];
      }
    }
    set(out);
    if (t < 1) {
      raf = requestAnimationFrame(tick);
    } else {
      set(text);
    }
  };
  raf = requestAnimationFrame(tick);
  return () => {
    if (raf) cancelAnimationFrame(raf);
  };
}