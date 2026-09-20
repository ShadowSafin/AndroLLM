"use client";

import type { MouseEvent, ReactNode } from "react";
import {
  motion,
  useMotionValue,
  useReducedMotion,
  useSpring,
  useTransform,
} from "framer-motion";
import { cn } from "@/lib/utils";

// Cursor-following "wobble" surface (upstream: the 21st.dev wobble-card
// component). Adapted to this project rather than pasted as-is:
//
//  · `motion/react` → `framer-motion`. This repo already ships framer-motion
//    v12 (see animations/reveal.tsx) and has no dependency on the newer
//    `motion` package, so importing `motion/react` here would not resolve.
//  · Per-mousemove React state → motion values. The upstream version calls
//    setState on every mousemove, re-rendering the card's whole subtree —
//    copy, icons, bullet lists — dozens of times a second. Motion values write
//    straight to the transform, so the card never re-renders while wobbling.
//  · Raw palette colors (bg-indigo-800, bg-pink-800, bg-blue-900) and a
//    hard-coded multi-stop shadow are gone. This project's contract (top of
//    app/globals.css) allows components to reference LAYER 2 tokens only, so
//    the default surface is `--surface` with a `--card-shadow` elevation, and
//    callers tint a card by passing `containerClassName`.
//  · The `url(/noise.webp)` reference is gone — that asset does not exist in
//    this repo, and an unpublished texture would 404. The grain layer reuses
//    the same inline SVG turbulence as `.grain` in globals.css, so it needs no
//    file and no network request.
//  · Reduced motion is honoured (project convention): the card holds still and
//    the hover scale is skipped, instead of ignoring the user's preference.

const SPRING = { stiffness: 260, damping: 26, mass: 0.6 } as const;

const GRAIN_TEXTURE =
  "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='160' height='160'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='2' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E\")";

export const WobbleCard = ({
  children,
  containerClassName,
  className,
}: {
  children: ReactNode;
  containerClassName?: string;
  className?: string;
}) => {
  const reduce = useReducedMotion();

  // Displacement of the frame, and of its contents in the opposite direction.
  const x = useMotionValue(0);
  const y = useMotionValue(0);
  const scale = useMotionValue(1);

  const frameX = useSpring(x, SPRING);
  const frameY = useSpring(y, SPRING);
  const frameScale = useSpring(scale, SPRING);
  const contentX = useTransform(frameX, (v) => -v);
  const contentY = useTransform(frameY, (v) => -v);

  const handleMouseMove = (event: MouseEvent<HTMLElement>) => {
    if (reduce) return;
    const rect = event.currentTarget.getBoundingClientRect();
    // Divisor 20 keeps the travel subtle: ±(~20px) at the card's edges.
    x.set((event.clientX - (rect.left + rect.width / 2)) / 20);
    y.set((event.clientY - (rect.top + rect.height / 2)) / 20);
  };

  return (
    <motion.section
      onMouseMove={handleMouseMove}
      onMouseEnter={() => {
        if (!reduce) scale.set(1.03);
      }}
      onMouseLeave={() => {
        x.set(0);
        y.set(0);
        scale.set(1);
      }}
      style={{ x: frameX, y: frameY }}
      className={cn(
        "relative mx-auto w-full overflow-hidden rounded-card border border-[var(--line)] bg-[var(--surface)]",
        containerClassName
      )}
    >
      <div
        className="relative h-full overflow-hidden rounded-card [background-image:radial-gradient(88%_100%_at_top,color-mix(in_srgb,var(--ink)_16%,transparent),transparent)]"
        style={{ boxShadow: "var(--card-shadow)" }}
      >
        <motion.div
          style={{ x: contentX, y: contentY, scale: frameScale }}
          className={cn("relative h-full px-6 py-10 sm:px-8 sm:py-12", className)}
        >
          <Grain />
          {children}
        </motion.div>
      </div>
    </motion.section>
  );
};

/** Film-grain layer — inline SVG, so it needs no texture asset. */
const Grain = () => (
  <div
    aria-hidden
    className="pointer-events-none absolute inset-0 h-full w-full scale-[1.2] transform opacity-[0.16] [-webkit-mask-image:radial-gradient(#fff,transparent,75%)] [mask-image:radial-gradient(#fff,transparent,75%)]"
    style={{ backgroundImage: GRAIN_TEXTURE, backgroundSize: "180px" }}
  />
);
