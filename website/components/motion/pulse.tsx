import type { ReactNode } from "react";

export function Pulse({ children, color = "var(--ok)", className, size = 8 }: { children?: ReactNode; color?: string; className?: string; size?: number }) {
  return (
    <span className={`relative inline-flex items-center gap-2 ${className ?? ""}`}>
      <span className="relative inline-flex">
        <span
          className="absolute inset-0 rounded-full opacity-60"
          style={{ background: color, animation: "pulseRing 1.8s cubic-bezier(0.215, 0.61, 0.355, 1) infinite" }}
        />
        <span
          className="relative inline-block rounded-full"
          style={{ width: size, height: size, background: color }}
        />
      </span>
      {children}
      <style>{`@keyframes pulseRing { 0% { transform: scale(0.6); opacity: 0.7; } 80% { transform: scale(2.4); opacity: 0; } 100% { opacity: 0; } }`}</style>
    </span>
  );
}

export function Shimmer({ className }: { className?: string }) {
  return (
    <div
      className={`pointer-events-none absolute inset-0 ${className ?? ""}`}
      style={{
        background:
          "linear-gradient(110deg, transparent 30%, color-mix(in srgb,var(--surface)_85%,white) 50%, transparent 70%)",
        backgroundSize: "200% 100%",
        animation: "shimmer 2.4s linear infinite",
      }}
      aria-hidden
    >
      <style>{`@keyframes shimmer { 0% { background-position: 200% 0; } 100% { background-position: -200% 0; } }`}</style>
    </div>
  );
}

export function Marquee({
  items,
  speed = 32,
  className,
  direction = "left",
  pauseOnHover = true,
}: {
  items: ReactNode[];
  speed?: number;
  className?: string;
  direction?: "left" | "right";
  pauseOnHover?: boolean;
}) {
  return (
    <div className={`group relative overflow-hidden ${className ?? ""}`} data-marquee>
      <div
        className={`flex min-w-max gap-6 ${pauseOnHover ? "group-hover:[animation-play-state:paused]" : ""}`}
        style={{
          animation: `marquee-${direction} ${speed}s linear infinite`,
        }}
      >
        {items.map((it, i) => (
          <div key={i} className="flex shrink-0 items-center">
            {it}
          </div>
        ))}
        {items.map((it, i) => (
          <div key={`dup-${i}`} className="flex shrink-0 items-center" aria-hidden>
            {it}
          </div>
        ))}
      </div>
      <style>{`
        @keyframes marquee-left { from { transform: translateX(0); } to { transform: translateX(-50%); } }
        @keyframes marquee-right { from { transform: translateX(-50%); } to { transform: translateX(0); } }
      `}</style>
    </div>
  );
}