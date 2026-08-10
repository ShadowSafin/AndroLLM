"use client";

import { useEffect, useRef, useState } from "react";

export function Typewriter({
  words,
  className,
  typingSpeed = 90,
  deletingSpeed = 45,
  pause = 1400,
}: {
  words: string[];
  className?: string;
  typingSpeed?: number;
  deletingSpeed?: number;
  pause?: number;
}) {
  const [text, setText] = useState("");
  const [wordIdx, setWordIdx] = useState(0);
  const [phase, setPhase] = useState<"typing" | "pausing" | "deleting">("typing");
  const idxRef = useRef(0);

  useEffect(() => {
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (reduce) {
      setText(words[0] ?? "");
      return;
    }
    const current = words[wordIdx] ?? "";

    let timer: ReturnType<typeof setTimeout>;
    if (phase === "typing") {
      if (idxRef.current < current.length) {
        timer = setTimeout(() => {
          idxRef.current += 1;
          setText(current.slice(0, idxRef.current));
        }, typingSpeed);
      } else {
        timer = setTimeout(() => setPhase("deleting"), pause);
      }
    } else if (phase === "deleting") {
      if (idxRef.current > 0) {
        timer = setTimeout(() => {
          idxRef.current -= 1;
          setText(current.slice(0, idxRef.current));
        }, deletingSpeed);
      } else {
        setPhase("typing");
        setWordIdx((i) => (i + 1) % words.length);
      }
    }
    return () => clearTimeout(timer);
  }, [text, phase, wordIdx, words, typingSpeed, deletingSpeed, pause]);

  return (
    <span className={className} aria-live="polite">
      <span className="text-[var(--ink)]">{text}</span>
      <span
        aria-hidden
        className="ml-0.5 inline-block h-[1em] w-[2px] translate-y-[2px] bg-[var(--accent)] align-middle"
        style={{ animation: "blink 1s steps(2) infinite" }}
      />
      <style>{`@keyframes blink { 50% { opacity: 0; } }`}</style>
    </span>
  );
}