"use client";

import { useRef, type ReactNode } from "react";
import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { useGSAP } from "@gsap/react";
import { easings, motionDurations } from "@/lib/motion";

gsap.registerPlugin(ScrollTrigger);

type Props = {
  children: ReactNode;
  className?: string;
  speed?: number;
  axis?: "y" | "x";
};

export function Parallax({ children, className, speed = 0.15, axis = "y" }: Props) {
  const ref = useRef<HTMLDivElement | null>(null);

  useGSAP(
    () => {
      const el = ref.current;
      if (!el) return;
      const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      if (reduce) return;

      gsap.fromTo(
        el,
        { [axis]: -60 * speed * 4 },
        {
          [axis]: 60 * speed * 4,
          ease: "none",
          scrollTrigger: {
            trigger: el,
            start: "top bottom",
            end: "bottom top",
            scrub: 0.6,
          },
        }
      );
    },
    { scope: ref }
  );

  return (
    <div ref={ref} className={className} style={{ willChange: "transform" }}>
      {children}
    </div>
  );
}

export function CountUp({ end, duration = motionDurations.base, className, prefix = "", suffix = "" }: { end: number; duration?: number; className?: string; prefix?: string; suffix?: string }) {
  const ref = useRef<HTMLSpanElement | null>(null);

  useGSAP(
    () => {
      const el = ref.current;
      if (!el) return;
      const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      const obj = { v: 0 };
      if (reduce) {
        el.textContent = `${prefix}${end.toLocaleString()}${suffix}`;
        return;
      }
      gsap.to(obj, {
        v: end,
        duration,
        ease: easings.roll,
        onUpdate: () => {
          el.textContent = `${prefix}${Math.round(obj.v).toLocaleString()}${suffix}`;
        },
      });
    },
    { scope: ref }
  );

  return <span ref={ref} className={className} />;
}

export function HoverTilt({ children, className, max = 6 }: { children: ReactNode; className?: string; max?: number }) {
  const ref = useRef<HTMLDivElement | null>(null);

  useGSAP(
    () => {
      const el = ref.current;
      if (!el) return;
      const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      const coarse = window.matchMedia("(pointer: coarse)").matches;
      if (reduce || coarse) return;
      const setX = gsap.quickTo(el, "rotateY", { duration: 0.45, ease: "power2.out" });
      const setY = gsap.quickTo(el, "rotateX", { duration: 0.45, ease: "power2.out" });

      const onMove = (e: PointerEvent) => {
        const rect = el.getBoundingClientRect();
        const rx = ((e.clientY - rect.top) / rect.height - 0.5) * -max * 2;
        const ry = ((e.clientX - rect.left) / rect.width - 0.5) * max * 2;
        setY(rx);
        setX(ry);
      };
      const onLeave = () => {
        setX(0);
        setY(0);
      };

      el.addEventListener("pointermove", onMove);
      el.addEventListener("pointerleave", onLeave);
      return () => {
        el.removeEventListener("pointermove", onMove);
        el.removeEventListener("pointerleave", onLeave);
      };
    },
    { scope: ref }
  );

  return (
    <div
      ref={ref}
      className={className}
      style={{ transformStyle: "preserve-3d", willChange: "transform" }}
    >
      {children}
    </div>
  );
}

export function ScrollProgress({ className, color = "var(--accent)" }: { className?: string; color?: string }) {
  const ref = useRef<HTMLDivElement | null>(null);

  useGSAP(
    () => {
      const el = ref.current;
      if (!el) return;
      ScrollTrigger.create({
        start: 0,
        end: "max",
        onUpdate: (self) => {
          el.style.transform = `scaleX(${self.progress})`;
        },
      });
    },
    { scope: ref }
  );

  return (
    <div
      ref={ref}
      className={`fixed left-0 top-0 z-[60] h-[3px] origin-left ${className ?? ""}`}
      style={{ background: `linear-gradient(90deg, ${color}, ${color} 60%, transparent)`, transform: "scaleX(0)" }}
      aria-hidden
    />
  );
}