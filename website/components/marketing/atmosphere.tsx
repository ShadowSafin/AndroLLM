"use client";

import { useEffect, useRef } from "react";
import { useReducedMotion } from "framer-motion";

export function Particles({ count = 34 }: { count?: number }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const reduce = useReducedMotion();

  useEffect(() => {
    if (reduce) return;
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    let raf = 0;
    let w = 0;
    let h = 0;

    const particles = Array.from({ length: count }, () => ({
      x: Math.random(),
      y: Math.random(),
      r: 0.6 + Math.random() * 1.8,
      vx: (Math.random() - 0.5) * 0.00018,
      vy: -0.00012 - Math.random() * 0.00028,
      a: 0.12 + Math.random() * 0.35,
      warm: Math.random() > 0.72,
    }));

    const resize = () => {
      const rect = canvas.getBoundingClientRect();
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      w = rect.width;
      h = rect.height;
      canvas.width = w * dpr;
      canvas.height = h * dpr;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    };

    const tick = () => {
      ctx.clearRect(0, 0, w, h);
      for (const p of particles) {
        p.x += p.vx;
        p.y += p.vy;
        if (p.y < -0.05) {
          p.y = 1.05;
          p.x = Math.random();
        }
        if (p.x < -0.05) p.x = 1.05;
        if (p.x > 1.05) p.x = -0.05;
        ctx.beginPath();
        ctx.arc(p.x * w, p.y * h, p.r, 0, Math.PI * 2);
        ctx.fillStyle = p.warm
          ? `rgba(217, 119, 87, ${p.a})`
          : `rgba(94, 93, 89, ${p.a * 0.55})`;
        ctx.fill();
      }
      raf = requestAnimationFrame(tick);
    };

    resize();
    tick();
    const onResize = () => resize();
    window.addEventListener("resize", onResize);
    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener("resize", onResize);
    };
  }, [count, reduce]);

  return (
    <canvas
      ref={canvasRef}
      className="pointer-events-none absolute inset-0 h-full w-full"
      aria-hidden
    />
  );
}

export function GradientBlobs() {
  return (
    <div className="pointer-events-none absolute inset-0 overflow-hidden" aria-hidden>
      <div
        className="absolute -top-32 left-1/2 h-[34rem] w-[54rem] -translate-x-1/2 rounded-full opacity-60 blur-3xl"
        style={{
          background:
            "radial-gradient(closest-side, rgba(217,119,87,0.28), rgba(217,119,87,0.10) 45%, transparent 70%)",
        }}
      />
      <div className="absolute -left-40 top-1/3 h-[26rem] w-[26rem] animate-blob-drift rounded-full blur-3xl" style={{ background: "radial-gradient(closest-side, rgba(230,157,129,0.16), transparent 70%)" }} />
      <div className="absolute -right-40 top-1/4 h-[30rem] w-[30rem] animate-blob-drift rounded-full blur-3xl [animation-delay:-11s]" style={{ background: "radial-gradient(closest-side, rgba(94,93,89,0.12), transparent 70%)" }} />
    </div>
  );
}

export function MouseLight({ className }: { className?: string }) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    let raf = 0;
    const onMove = (e: MouseEvent) => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(() => {
        el.style.background = `radial-gradient(600px circle at ${e.clientX}px ${e.clientY}px, rgba(217,119,87,0.07), transparent 55%)`;
      });
    };
    window.addEventListener("mousemove", onMove);
    return () => {
      window.removeEventListener("mousemove", onMove);
      cancelAnimationFrame(raf);
    };
  }, []);

  return <div ref={ref} className={`pointer-events-none fixed inset-0 z-[1] ${className ?? ""}`} aria-hidden />;
}