"use client";

import { useEffect, useRef } from "react";
import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { useGSAP } from "@gsap/react";
import { motionDurations } from "@/lib/motion";

gsap.registerPlugin(ScrollTrigger);

export function SvgDrawOn({
  children,
  className,
  delay = 0,
  duration = motionDurations.epic,
  staggerPaths = 0.18,
}: {
  children: React.ReactNode;
  className?: string;
  delay?: number;
  duration?: number;
  staggerPaths?: number;
}) {
  const ref = useRef<SVGSVGElement | null>(null);

  useGSAP(
    () => {
      const root = ref.current;
      if (!root) return;
      const paths = Array.from(root.querySelectorAll<SVGGeometryElement>("path, line, polyline, polygon, circle, rect, ellipse"));
      if (paths.length === 0) return;

      const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      if (reduce) {
        gsap.set(paths, { strokeDashoffset: 0 });
        return;
      }

      paths.forEach((p) => {
        const length = p.getTotalLength?.() ?? 1000;
        p.style.strokeDasharray = `${length}`;
        p.style.strokeDashoffset = `${length}`;
      });
      gsap.set(paths, { opacity: 0.4 });

      ScrollTrigger.create({
        trigger: root,
        start: "top 85%",
        once: true,
        animation: gsap.to(paths, {
          strokeDashoffset: 0,
          opacity: 1,
          duration,
          ease: "power2.inOut",
          stagger: staggerPaths,
          delay,
        }),
      });
    },
    { scope: ref }
  );

  return (
    <svg ref={ref as any} className={className} aria-hidden>
      {children}
    </svg>
  );
}