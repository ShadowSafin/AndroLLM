// ── Grid veil — static square grid behind the page ──────────────────────────
// Replaces the WebGL ink field with a Vercel-style hairline grid. No canvas,
// no requestAnimationFrame loop, no WebGL context to lose, nothing to compile
// at runtime. The grid is anchored to the top of the viewport and masked so it
// fades out before it competes with copy; a single accent bloom sits where the
// old field's light was.
//
// All of it is CSS (see `.grid-veil` / `.grid-bloom` in globals.css), so this
// stays a server component and ships zero JavaScript.

export function GridField({ className }: { className?: string }) {
  return (
    <div
      aria-hidden
      className={`pointer-events-none fixed inset-0 -z-10 overflow-hidden ${className ?? ""}`}
    >
      <div className="grid-veil absolute inset-0" />
      <div className="grid-bloom absolute inset-x-0 top-0 h-[560px]" />
    </div>
  );
}
