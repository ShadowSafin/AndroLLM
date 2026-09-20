"use client";

import { useEffect, useRef } from "react";
import { gsap } from "gsap";

export function AuroraBackground({
  variant = "parchment",
  className,
}: {
  variant?: "parchment" | "midnight" | "ember" | "dawn";
  className?: string;
}) {
  const ref = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const root = ref.current;
    if (!root) return;
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const orbs = Array.from(root.querySelectorAll<HTMLElement>("[data-orb]"));
    if (reduce) return;

    const tweens: gsap.core.Tween[] = [];
    orbs.forEach((orb, i) => {
      const duration = 18 + i * 4;
      tweens.push(
        gsap.to(orb, {
          x: `+=${gsap.utils.random(-80, 80)}`,
          y: `+=${gsap.utils.random(-60, 60)}`,
          scale: gsap.utils.random(0.85, 1.18),
          rotation: gsap.utils.random(-25, 25),
          duration,
          ease: "sine.inOut",
          repeat: -1,
          yoyo: true,
        })
      );
    });
    return () => tweens.forEach((t) => t.kill());
  }, []);

  const palette = {
    parchment: ["#7a5cff", "#9d85ff", "#cfc2ff", "#f25dff"],
    midnight: ["#7a5cff", "#5b3fd6", "#9d85ff", "#f25dff"],
    ember: ["#7a5cff", "#9d85ff", "#cfc2ff", "#f25dff"],
    dawn: ["#cfc2ff", "#9d85ff", "#7a5cff", "#f25dff"],
  }[variant];

  return (
    <div
      ref={ref}
      aria-hidden
      className={`pointer-events-none absolute inset-0 overflow-hidden ${className ?? ""}`}
    >
      <svg
        className="absolute inset-0 size-full opacity-[0.18] mix-blend-multiply dark:opacity-[0.28] dark:mix-blend-screen"
        xmlns="http://www.w3.org/2000/svg"
      >
        <defs>
          <pattern id="grid-fine" width="32" height="32" patternUnits="userSpaceOnUse">
            <path d="M 32 0 L 0 0 0 32" fill="none" stroke="currentColor" strokeWidth="0.4" />
          </pattern>
          <pattern id="grid-rough" width="128" height="128" patternUnits="userSpaceOnUse">
            <path d="M 128 0 L 0 0 0 128" fill="none" stroke="currentColor" strokeWidth="0.7" />
          </pattern>
        </defs>
        <rect width="100%" height="100%" fill="url(#grid-fine)" />
        <rect width="100%" height="100%" fill="url(#grid-rough)" />
      </svg>

      {palette.map((color, i) => (
        <div
          key={i}
          data-orb
          className="absolute rounded-full blur-3xl opacity-50"
          style={{
            width: 320 + i * 60,
            height: 320 + i * 60,
            left: `${15 + i * 18}%`,
            top: `${10 + (i % 2) * 40}%`,
            background: `radial-gradient(circle at center, ${color}66 0%, transparent 65%)`,
          }}
        />
      ))}

      <div className="absolute inset-0 bg-[radial-gradient(circle_at_50%_100%,transparent_30%,color-mix(in_srgb,var(--bg)_85%,transparent)_100%)]" />
    </div>
  );
}