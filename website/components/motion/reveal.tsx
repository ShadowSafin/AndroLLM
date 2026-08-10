"use client";

import { useRef, type JSX, type ReactNode } from "react";
import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { useGSAP } from "@gsap/react";
import { easings, motionDurations, motionStaggers, motionDistances } from "@/lib/motion";

gsap.registerPlugin(useGSAP, ScrollTrigger);

type Direction = "up" | "down" | "left" | "right" | "none";
type Variant = "fade" | "slide" | "rise" | "tilt" | "blur" | "scale" | "flip";

type RevealProps = {
  children: ReactNode;
  className?: string;
  as?: keyof JSX.IntrinsicElements;
  delay?: number;
  duration?: number;
  distance?: number;
  variant?: Variant;
  direction?: Direction;
  stagger?: number | null;
  once?: boolean;
  threshold?: number;
  scrub?: boolean | number;
  pin?: boolean;
  pinnedChild?: ReactNode;
};

const offsetFor = (variant: Variant, direction: Direction, distance: number) => {
  if (variant === "fade") return { x: 0, y: 0, scale: 1, rotate: 0 };
  if (variant === "rise") return direction === "down" ? { x: 0, y: -distance } : { x: 0, y: distance };
  if (variant === "slide") {
    if (direction === "left") return { x: -distance, y: 0 };
    if (direction === "right") return { x: distance, y: 0 };
    if (direction === "down") return { x: 0, y: -distance };
    return { x: 0, y: distance };
  }
  if (variant === "scale") return { x: 0, y: 0, scale: 0.94 };
  if (variant === "tilt") return { x: 0, y: 0, rotate: -3 };
  if (variant === "flip") return { x: 0, y: 0, rotateX: 8 };
  return { x: 0, y: distance };
};

export function Reveal({
  children,
  className,
  as = "div",
  delay = 0,
  duration = motionDurations.base,
  distance = motionDistances.base,
  variant = "rise",
  direction = "up",
  stagger = null,
  once = true,
  threshold = 0.18,
  scrub = false,
  pin = false,
  pinnedChild,
}: RevealProps) {
  const ref = useRef<HTMLElement | null>(null);

  useGSAP(
    () => {
      const root = ref.current;
      if (!root) return;
      const targets = stagger != null ? Array.from(root.children) as Element[] : [root];
      const offset = offsetFor(variant, direction, distance);
      const mm = gsap.matchMedia();

      mm.add("(prefers-reduced-motion: reduce)", () => {
        gsap.set(targets, { clearProps: "all", opacity: 1, x: 0, y: 0, scale: 1, rotate: 0, rotateX: 0, filter: "none" });
      });

      mm.add("(prefers-reduced-motion: no-preference)", () => {
        gsap.set(targets, { opacity: 0, ...offset, filter: variant === "blur" ? "blur(6px)" : "none" });

        const triggerConfig: ScrollTrigger.Vars = {
          trigger: root,
          start: `top ${Math.round((1 - threshold) * 100)}%`,
          toggleActions: once ? "play none none none" : "play reverse play reverse",
        };

        if (scrub) triggerConfig.scrub = scrub === true ? 1 : scrub;
        if (pin) {
          triggerConfig.pin = true;
          triggerConfig.pinSpacing = true;
          triggerConfig.end = "+=600";
        }

        const tweenConfig = {
          opacity: 1,
          x: 0,
          y: 0,
          scale: 1,
          rotate: 0,
          rotateX: 0,
          filter: "blur(0px)",
          duration,
          ease: easings.smooth,
          stagger: stagger ?? 0,
          delay,
        };

        if (scrub) {
          gsap.to(targets, { ...tweenConfig, scrollTrigger: triggerConfig });
        } else {
          ScrollTrigger.create({
            ...triggerConfig,
            animation: gsap.to(targets, tweenConfig),
          });
        }
      });

      return () => mm.revert();
    },
    { scope: ref }
  );

  const Tag = as as any;

  return (
    <Tag ref={ref as any} className={className} data-reveal={variant}>
      {children}
      {pin && pinnedChild ? <div className="sr-only" aria-hidden>{pinnedChild}</div> : null}
    </Tag>
  );
}

export function RevealStagger({
  children,
  className,
  delay = 0,
  duration = motionDurations.base,
  distance = motionDistances.small,
  variant = "rise",
  threshold = 0.12,
  staggerChildren = motionStaggers.base,
  once = true,
}: {
  children: ReactNode;
  className?: string;
  delay?: number;
  duration?: number;
  distance?: number;
  variant?: Variant;
  threshold?: number;
  staggerChildren?: number;
  once?: boolean;
}) {
  const ref = useRef<HTMLDivElement | null>(null);

  useGSAP(
    () => {
      const root = ref.current;
      if (!root) return;
      const offset = offsetFor(variant, "up", distance);
      const mm = gsap.matchMedia();

      mm.add("(prefers-reduced-motion: reduce)", () => {
        gsap.set(root.children, { clearProps: "all", opacity: 1, x: 0, y: 0 });
      });

      mm.add("(prefers-reduced-motion: no-preference)", () => {
        const items = Array.from(root.children) as HTMLElement[];
        gsap.set(items, { opacity: 0, y: offset.y });

        ScrollTrigger.create({
          trigger: root,
          start: `top ${Math.round((1 - threshold) * 100)}%`,
          toggleActions: once ? "play none none none" : "play reverse play reverse",
          animation: gsap.to(items, {
            opacity: 1,
            y: 0,
            duration,
            ease: easings.smooth,
            stagger: staggerChildren,
            delay,
          }),
        });
      });

      return () => mm.revert();
    },
    { scope: ref }
  );

  return (
    <div ref={ref} className={className} data-reveal-stagger>
      {children}
    </div>
  );
}