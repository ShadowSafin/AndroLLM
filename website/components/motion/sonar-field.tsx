"use client";

import { SonarGrid } from "@/components/ui/sonar-grid";

// ── Sonar field — the site-wide backdrop ────────────────────────────────────
// Replaces the former line-grid veil behind every route.
//
// Interaction runs through `pointerSource="window"` rather than the component's
// upstream host listener. A backdrop one layer down cannot be a hit target at
// all: at a negative z-index it paints behind the body box, and `body` is
// hit-testable, so it always wins the hit test. Listening on `window` is also
// strictly safer — the event still reaches its real target, so no click on a
// link, button, or form field is ever intercepted. It does mean a ping fires
// for taps anywhere on the page, not only on empty space, which is the point.
//
// The dots stay monochrome (`var(--ink)`) rather than brand violet: the
// backdrop layer is monochrome throughout this design system, with the accent
// reserved for CTAs and the single bloom, so a violet field would fight it.
// Tuned well below the component's defaults — this is ambient ground, not a hero.
//
// The field deliberately leaves the pointer cursor alone. It used to add a
// `sonar-cursor` class to <body> that set `cursor: crosshair` across the whole
// site — and since the field is inert and never a hit target, that body rule was
// the only way the class could ever apply (the component's own
// `cursor-crosshair` sits behind `pointer-events-none`). An HUD crosshair over
// every heading, paragraph and form field reads as a game overlay rather than a
// documentation site, so the cursor is now the platform default. The pings are
// unaffected: they still fire from the window-level pointer listener.

export function SonarField({ className }: { className?: string }) {
  return (
    <div
      aria-hidden
      className={`pointer-events-none fixed inset-0 -z-10 overflow-hidden ${className ?? ""}`}
    >
      <SonarGrid
        className="size-full"
        color="var(--ink)"
        spacing={32}
        dotRadius={1.1}
        baseOpacity={0.16}
        pingEvery={3.2}
        speed={240}
        ringWidth={110}
        amplitude={2}
        interactive
        pointerSource="window"
        pingArea={[0.12, 0.08, 0.88, 0.8]}
      />
      <div className="field-bloom absolute inset-x-0 top-0 h-[560px]" />
    </div>
  );
}
