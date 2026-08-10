"use client";

import { useRef } from "react";
import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { useGSAP } from "@gsap/react";
import { easings } from "@/lib/motion";

gsap.registerPlugin(ScrollTrigger);

export function PerformanceBars({
  bars,
  className,
  delay = 0.15,
  duration = 1.1,
}: {
  bars: { label: string; value: number; suffix?: string; color?: string; right?: string }[];
  className?: string;
  delay?: number;
  duration?: number;
}) {
  const ref = useRef<HTMLDivElement | null>(null);

  useGSAP(
    () => {
      const root = ref.current;
      if (!root) return;
      const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      const fillers = Array.from(root.querySelectorAll<HTMLElement>("[data-fill]"));
      if (reduce) {
        fillers.forEach((el) => {
          const target = el.dataset.target ?? "0";
          el.style.width = `${target}%`;
        });
        return;
      }
      gsap.set(fillers, { width: 0 });
      ScrollTrigger.create({
        trigger: root,
        start: "top 80%",
        once: true,
        animation: gsap.to(fillers, {
          width: (i) => `${parseFloat(bars[i]?.value.toString() ?? "0")}%`,
          duration,
          ease: easings.roll,
          stagger: 0.12,
          delay,
        }),
      });
    },
    { scope: ref, dependencies: [bars, delay, duration] }
  );

  return (
    <div ref={ref} className={`space-y-4 ${className ?? ""}`}>
      {bars.map((bar, i) => (
        <div key={bar.label}>
          <div className="mb-1.5 flex items-baseline justify-between gap-4">
            <span className="text-sm font-medium text-[var(--ink-dim)]">{bar.label}</span>
            <span className="font-mono text-xs text-[var(--faint)]">
              {bar.right ?? `${bar.value}${bar.suffix ?? "%"}`}
            </span>
          </div>
          <div className="h-2.5 overflow-hidden rounded-full bg-[var(--line-soft)]">
            <div
              data-fill
              data-target={bar.value}
              className="h-full rounded-full"
              style={{
                background: `linear-gradient(90deg, ${bar.color ?? "var(--accent)"}, ${bar.color ?? "var(--accent)"} 70%, transparent)`,
                width: 0,
              }}
            />
          </div>
        </div>
      ))}
    </div>
  );
}

export function Waveform({
  bars = 48,
  className,
  color = "var(--accent-deep)",
  speed = 1.1,
}: {
  bars?: number;
  className?: string;
  color?: string;
  speed?: number;
}) {
  return (
    <div className={`flex h-16 items-center gap-[3px] overflow-hidden ${className ?? ""}`} aria-hidden>
      {Array.from({ length: bars }).map((_, i) => (
        <span
          key={i}
          className="w-[3px] shrink-0 rounded-full"
          style={{
            background: color,
            height: "12%",
            animation: `wavebar ${speed}s ease-in-out ${i * 0.045}s infinite alternate`,
          }}
        />
      ))}
      <style>{`
        @keyframes wavebar {
          0% { height: 8%; opacity: 0.5; }
          50% { height: 92%; opacity: 1; }
          100% { height: 30%; opacity: 0.7; }
        }
      `}</style>
    </div>
  );
}

export function Orbit({
  className,
  items,
  radius = 90,
  duration = 26,
}: {
  className?: string;
  items?: string[];
  radius?: number;
  duration?: number;
}) {
  return (
    <div className={`relative ${className ?? ""}`} aria-hidden>
      <span className="absolute inset-0 rounded-full border border-[var(--line)]" />
      <span className="absolute inset-4 rounded-full border border-[var(--line-soft)]" />
      {items?.map((label, i) => {
        const angle = (i / (items.length || 1)) * 360;
        return (
          <div
            key={label}
            className="absolute left-1/2 top-1/2"
            style={{ transform: `translate(-50%, -50%)` }}
          >
            <span
              className="absolute inline-block whitespace-nowrap rounded-full border border-[var(--line)] bg-[var(--surface)] px-2.5 py-1 font-mono text-[11px] text-[var(--muted)] shadow-card"
              style={{
                transform: `rotate(${angle}deg) translateX(${radius}px) rotate(-${angle}deg)`,
                animation: `orbitspin ${duration}s linear infinite`,
              }}
            >
              {label}
            </span>
          </div>
        );
      })}
      <style>{`@keyframes orbitspin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }`}</style>
    </div>
  );
}

export function DashCounter({ value, className, color = "var(--accent)" }: { value: number; className?: string; color?: string }) {
  const ref = useRef<HTMLDivElement | null>(null);
  useGSAP(
    () => {
      const root = ref.current;
      if (!root) return;
      const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      const dash = root.querySelector<SVGCircleElement>("circle");
      const txt = root.querySelector<HTMLElement>("[data-dash-value]");
      if (!dash) return;
      const len = 2 * Math.PI * Number(dash.getAttribute("r") ?? 0);
      const target = Math.max(0, Math.min(1, value));
      if (reduce) {
        gsap.set(dash, { strokeDasharray: `${len * target} ${len}` });
        if (txt) txt.textContent = `${Math.round(target * 100)}%`;
        return;
      }
      const obj = { p: 0 };
      gsap.to(obj, {
        p: target,
        duration: 1.6,
        ease: "power2.out",
        scrollTrigger: { trigger: root, start: "top 85%", once: true },
        onUpdate: () => {
          gsap.set(dash, { strokeDasharray: `${len * obj.p} ${len}` });
          if (txt) txt.textContent = `${Math.round(obj.p * 100)}%`;
        },
      });
    },
    { scope: ref }
  );
  return (
    <div ref={ref} className={`relative ${className ?? ""}`}>
      <svg viewBox="0 0 80 80" className="size-20 -rotate-90">
        <circle cx="40" cy="40" r="34" fill="none" stroke="var(--line)" strokeWidth="6" />
        <circle cx="40" cy="40" r="34" fill="none" stroke={color} strokeWidth="6" strokeLinecap="round" strokeDasharray={`0 213.6`} />
      </svg>
      <span data-dash-value className="absolute inset-0 grid place-items-center font-mono text-lg font-bold text-[var(--ink)]">
        0%
      </span>
    </div>
  );
}