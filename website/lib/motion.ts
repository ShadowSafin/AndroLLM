import type {
  Back,
  Bounce,
  Circ,
  Elastic,
  Expo,
  Power0,
  Power1,
  Power2,
  Power3,
  Power4,
  Sine,
  SteppedEase,
} from "gsap";

export type EasingKey =
  | "none"
  | "in"
  | "out"
  | "in-out"
  | "power1"
  | "power2"
  | "power3"
  | "power4"
  | "back"
  | "elastic"
  | "bounce"
  | "circ"
  | "expo"
  | "sine"
  | "steps";

export const motionDurations = {
  instant: 0.001,
  micro: 0.18,
  small: 0.32,
  base: 0.55,
  large: 0.8,
  epic: 1.2,
} as const;

export const motionStaggers = {
  tight: 0.02,
  small: 0.04,
  base: 0.07,
  wide: 0.12,
  dramatic: 0.18,
} as const;

export const motionDistances = {
  micro: 6,
  small: 12,
  base: 22,
  large: 36,
  hero: 56,
} as const;

export type EasingFn =
  | "none"
  | "power1.in"
  | "power1.out"
  | "power1.inOut"
  | "power2.in"
  | "power2.out"
  | "power2.inOut"
  | "power3.in"
  | "power3.out"
  | "power3.inOut"
  | "power4.in"
  | "power4.out"
  | "power4.inOut"
  | "back.in(1.7)"
  | "back.out(1.7)"
  | "back.inOut(1.7)"
  | "back.out(1.4)"
  | "back.out(2)"
  | "elastic.out(1, 0.5)"
  | "elastic.out(1, 0.4)"
  | "bounce.out"
  | "bounce.in"
  | "bounce.inOut"
  | "circ.inOut"
  | "expo.out"
  | "expo.inOut"
  | "sine.inOut";

export const easings = {
  smooth: "power2.out" as const,
  punchy: "back.out(1.4)" as const,
  silky: "power3.inOut" as const,
  snappy: "power4.out" as const,
  echo: "elastic.out(1, 0.4)" as const,
  pop: "back.out(2)" as const,
  breathe: "sine.inOut" as const,
  drift: "power1.inOut" as const,
  roll: "expo.out" as const,
} satisfies Record<string, EasingFn>;

export type EasingPreset = keyof typeof easings;

export const prefersReducedMotion = () =>
  typeof window !== "undefined" &&
  window.matchMedia("(prefers-reduced-motion: reduce)").matches;

export type Easing =
  | string
  | typeof Power0
  | typeof Power1
  | typeof Power2
  | typeof Power3
  | typeof Power4
  | typeof Elastic
  | typeof Bounce
  | typeof Circ
  | typeof Expo
  | typeof Sine
  | typeof Back
  | typeof CustomEase
  | typeof RoughEase
  | typeof ExpoScaleEase
  | typeof SlowMo
  | typeof SteppedEase;