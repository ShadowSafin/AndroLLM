"use client";

import { useEffect, useId, useRef, useState, type MouseEvent } from "react";
import {
  animate,
  motion,
  useReducedMotion,
  type AnimationPlaybackControls,
} from "framer-motion";

// ── TextHoverEffect ─────────────────────────────────────────────────────────
// A cursor-revealed wordmark (upstream: the 21st.dev "text hover effect").
// Three stacked copies of one string: a ghost outline that fades in on hover, a
// faint fixed outline, and a gradient-filled copy revealed only under a radial
// mask that tracks the pointer.
//
// Adapted to this repo rather than pasted as-is:
//
//  · `motion/react` → `framer-motion`. This project ships framer-motion v12 and
//    does not depend on the newer `motion` package (see animations/reveal.tsx).
//  · Cursor tracking left React state. Upstream keeps the pointer in state and
//    recomputes the mask centre in an effect, so every mousemove re-renders all
//    three <text> nodes in order to produce two SVG attributes. Here the mask
//    centre is written straight onto the gradient; only enter/leave — once per
//    hover, not per frame — touch state.
//  · Upstream's rainbow (#eab308 → #ef4444 → #3b82f6 → #06b6d4 → #8b5cf6,
//    yellow through violet) and its `stroke-neutral-200 dark:stroke-neutral-800`
//    outlines were outside this project's palette. The gradient now walks the
//    aurora ramp and the outlines use LAYER 2 tokens, per the contract at the
//    top of app/globals.css.
//  · `font-[helvetica]` → the site's Geist (`font-geist`).
//  · Gradient and mask ids are namespaced with `useId()`. Upstream hard-codes
//    `textMask` / `revealMask` / `textGradient`, so two instances on one page
//    would silently share the first one's mask.
//  · The word is no longer clipped. Upstream hard-codes `viewBox="0 0 300 100"`
//    with a fixed 72px font: about four characters fit, and an eight-letter word
//    like "AndroLLM" overruns the box — which SVG clips at its boundary, cutting
//    the outer letters off. The box is wider here and every <text> is bound to it
//    with `textLength` + `lengthAdjust="spacing"`, so a longer string fits by
//    widening the gaps between letters rather than distorting glyphs. The wrapper
//    supplies the box's aspect ratio (see the site footer for the pattern).
//  · Reduced motion is honoured (project convention): no intro and the reveal
//    parks at the centre instead of chasing the pointer.
//  · The upstream `automatic` prop was never read — it did nothing. It is gone.

/** Upstream's box is 3:1; this one is 4.6:1 so a full brand name fits inside it. */
const BOX = { width: 460, height: 100 } as const;
const EDGE_PADDING = 20;
const TEXT_LENGTH = BOX.width - EDGE_PADDING * 2;
const MAX_FONT_SIZE = 72;

/** The aurora ramp, read in the order the tokens are meant to be traversed. */
const GRADIENT_STOPS = [
  { offset: "0%", color: "var(--accent)" },
  { offset: "35%", color: "var(--accent-soft)" },
  { offset: "65%", color: "var(--accent-deep)" },
  { offset: "100%", color: "var(--accent-alt)" },
] as const;

export const TextHoverEffect = ({
  text,
  duration,
}: {
  text: string;
  duration?: number;
}) => {
  // `useId()` output is not URL-safe across React versions (":" in 18, "«»" in
  // 19), and these ids are consumed by `url(#…)` references, so strip everything
  // that is not an id-safe character.
  const uid = useId().replace(/[^a-zA-Z0-9_-]/g, "");
  const gradientId = `text-gradient-${uid}`;
  const revealId = `text-reveal-${uid}`;
  const maskId = `text-mask-${uid}`;

  const reduce = useReducedMotion();
  const revealRef = useRef<SVGRadialGradientElement | null>(null);
  const followRef = useRef<AnimationPlaybackControls[]>([]);
  // Mask centre, in percent of the rendered box — the unit the gradient takes.
  const centre = useRef({ cx: 50, cy: 50 });
  const [hovered, setHovered] = useState(false);

  useEffect(() => () => followRef.current.forEach((c) => c.stop()), []);

  const setCentre = (cx: number, cy: number) => {
    centre.current = { cx, cy };
    const node = revealRef.current;
    if (!node) return;
    node.setAttribute("cx", `${cx}%`);
    node.setAttribute("cy", `${cy}%`);
  };

  const handleMouseMove = (event: MouseEvent<SVGSVGElement>) => {
    if (reduce) return;
    const rect = event.currentTarget.getBoundingClientRect();
    if (!rect.width || !rect.height) return;

    const target = {
      cx: ((event.clientX - rect.left) / rect.width) * 100,
      cy: ((event.clientY - rect.top) / rect.height) * 100,
    };

    // `duration` is the upstream glide knob; unset means "sit under the pointer".
    if (!duration) {
      setCentre(target.cx, target.cy);
      return;
    }

    const from = centre.current;
    followRef.current.forEach((c) => c.stop());
    followRef.current = [
      animate(0, 1, {
        duration,
        ease: "easeOut",
        onUpdate: (t) =>
          setCentre(
            from.cx + (target.cx - from.cx) * t,
            from.cy + (target.cy - from.cy) * t
          ),
      }),
    ];
  };

  // Fits the word inside the box at any length: the nominal size (upstream's 72)
  // steps down once a string's natural advances would overrun the textLength
  // budget, so `lengthAdjust="spacing"` can only ever widen the gaps — never
  // collapse letters into each other. 0.62em is a deliberately conservative
  // average advance for Geist.
  const fontSize = Math.min(
    MAX_FONT_SIZE,
    TEXT_LENGTH / (Math.max(text.length, 1) * 0.62)
  );

  // Shared geometry: all three copies must use the same string, box and
  // textLength, or they stop lining up with each other.
  const textFit = {
    x: "50%",
    y: "50%",
    textAnchor: "middle" as const,
    dominantBaseline: "middle" as const,
    textLength: TEXT_LENGTH,
    lengthAdjust: "spacing" as const,
    strokeWidth: 0.3,
    fontSize,
  };

  return (
    <svg
      width="100%"
      height="100%"
      viewBox={`0 0 ${BOX.width} ${BOX.height}`}
      xmlns="http://www.w3.org/2000/svg"
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      onMouseMove={handleMouseMove}
      role="img"
      aria-label={text}
      className="block select-none"
    >
      <defs>
        <linearGradient id={gradientId} gradientUnits="userSpaceOnUse">
          {GRADIENT_STOPS.map((stop) => (
            <stop key={stop.offset} offset={stop.offset} stopColor={stop.color} />
          ))}
        </linearGradient>

        {/* Reveal mask. The stops are mask luminance, not brand color: white
            reveals, black hides. They stay literal so the mask cannot invert
            itself if the ink/canvas tokens ever swap themes. */}
        <radialGradient
          ref={revealRef}
          id={revealId}
          gradientUnits="userSpaceOnUse"
          cx="50%"
          cy="50%"
          r="15%"
        >
          <stop offset="0%" stopColor="white" />
          <stop offset="100%" stopColor="black" />
        </radialGradient>
        <mask id={maskId}>
          <rect x="0" y="0" width="100%" height="100%" fill={`url(#${revealId})`} />
        </mask>
      </defs>

      {/* Ghost outline — appears only while the pointer is inside. */}
      <text
        {...textFit}
        className="font-geist fill-transparent stroke-[var(--muted)] font-semibold"
        style={{ opacity: hovered ? 0.7 : 0 }}
        aria-hidden
      >
        {text}
      </text>

      {/* Standing outline, drawn on once. */}
      <motion.text
        {...textFit}
        className="font-geist fill-transparent stroke-[var(--faint)] font-semibold"
        initial={reduce ? false : { strokeDashoffset: 1000, strokeDasharray: 1000 }}
        animate={{ strokeDashoffset: 0, strokeDasharray: 1000 }}
        transition={reduce ? { duration: 0 } : { duration: 4, ease: "easeInOut" }}
        aria-hidden
      >
        {text}
      </motion.text>

      {/* Gradient fill, visible only where the mask reveals it. */}
      <text
        {...textFit}
        stroke={`url(#${gradientId})`}
        mask={`url(#${maskId})`}
        className="font-geist fill-transparent font-semibold"
        aria-hidden
      >
        {text}
      </text>
    </svg>
  );
};
