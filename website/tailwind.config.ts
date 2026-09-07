import type { Config } from "tailwindcss";
import defaultTheme from "tailwindcss/defaultTheme";

const config: Config = {
  darkMode: ["class"],
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}", "./lib/**/*.{ts,tsx}", "./content/**/*.{md,mdx}"],
  theme: {
    container: {
      center: true,
      padding: { DEFAULT: "1.25rem", sm: "1.5rem", lg: "2rem" },
      screens: { "2xl": "1280px" },
    },
    extend: {
      colors: {
        // Monochrome — black/white/gray only
        parchment: {
          canvas: "#000000",
          raised: "#0A0A0A",
          deep: "#0A0A0A",
          surface: "#111111",
          elevated: "#171717",
          border: "#262626",
          borderSoft: "#171717",
        },
        ink: {
          DEFAULT: "#FFFFFF",
          dim: "#E5E5E5",
          muted: "#9CA3AF",
          faint: "#6B7280",
        },
        ember: {
          DEFAULT: "#FFFFFF",
          light: "#A3A3A3",
          deep: "#FFFFFF",
          halo: "#1AFFFFFF",
        },
        lamp: {
          DEFAULT: "#FFFFFF",
        },
        night: {
          canvas: "#000000",
          surface: "#111111",
          raised: "#0A0A0A",
          border: "#262626",
          borderSoft: "#171717",
        },
        ok: { DEFAULT: "#E5E5E5" },
        warn: { DEFAULT: "#9CA3AF" },
        err: { DEFAULT: "#FFFFFF" },
      },
      fontFamily: {
        sans: ["var(--font-geist)", ...defaultTheme.fontFamily.sans],
        serif: ["var(--font-geist)", ...defaultTheme.fontFamily.sans],
        mono: ["var(--font-jetbrains)", ...defaultTheme.fontFamily.mono],
        geist: ["var(--font-geist)", ...defaultTheme.fontFamily.sans],
      },
      fontSize: {
        // Prompt typography: tight tracking, leading-none, 5xl→8xl hero scale
        "display-xl": ["clamp(3rem, 7vw, 6rem)", { lineHeight: "1", letterSpacing: "-0.05em" }],
        "display-lg": ["clamp(2.25rem, 5vw, 4.5rem)", { lineHeight: "1", letterSpacing: "-0.05em" }],
        "display-md": ["clamp(1.875rem, 4vw, 3rem)", { lineHeight: "1.05", letterSpacing: "-0.025em" }],
      },
      borderRadius: {
        card: "16px",
        slip: "10px",
        pill: "32px",
        small: "8px",
      },
      boxShadow: {
        card: "var(--card-shadow)",
        cardHover: "var(--card-shadow-hover)",
        ember: "var(--shadow-ember)",
        emberFloat: "var(--shadow-ember-float)",
        emberFloatSoft: "var(--shadow-ember-float-soft)",
        nav: "var(--nav-shadow)",
      },
      backgroundImage: {
        "ember-glow": "radial-gradient(1200px 600px at 50% -10%, rgba(255,255,255,0.08), transparent 60%)",
        "grid-parchment":
          "linear-gradient(to right, rgba(255,255,255,0.06) 1px, transparent 1px), linear-gradient(to bottom, rgba(255,255,255,0.06) 1px, transparent 1px)",
      },
      keyframes: {
        "blob-drift": {
          "0%, 100%": { transform: "translate(0, 0) scale(1)" },
          "33%": { transform: "translate(6%, -8%) scale(1.08)" },
          "66%": { transform: "translate(-5%, 6%) scale(0.95)" },
        },
        "float-slow": {
          "0%, 100%": { transform: "translateY(0)" },
          "50%": { transform: "translateY(-10px)" },
        },
        "marquee": {
          "0%": { transform: "translateX(0)" },
          "100%": { transform: "translateX(-50%)" },
        },
        "ember-breathe": {
          "0%, 100%": { opacity: "0.55", transform: "scale(1)" },
          "50%": { opacity: "1", transform: "scale(1.15)" },
        },
        "wave-bar": {
          "0%, 100%": { transform: "scaleY(0.3)" },
          "50%": { transform: "scaleY(1)" },
        },
        "aurora": {
          "0%, 100%": { backgroundPosition: "0% 50%" },
          "50%": { backgroundPosition: "100% 50%" },
        },
        "shimmer": {
          "0%": { backgroundPosition: "-200% 0" },
          "100%": { backgroundPosition: "200% 0" },
        },
        "progress-scan": {
          "0%": { transform: "translateX(-100%)" },
          "100%": { transform: "translateX(400%)" },
        },
        "pulse-ring": {
          "0%": { transform: "scale(0.8)", opacity: "0.8" },
          "100%": { transform: "scale(2.2)", opacity: "0" },
        },
        "grain": {
          "0%, 100%": { transform: "translate(0, 0)" },
          "10%": { transform: "translate(-2%, 3%)" },
          "20%": { transform: "translate(3%, -2%)" },
          "30%": { transform: "translate(-3%, -3%)" },
          "40%": { transform: "translate(2%, 2%)" },
          "50%": { transform: "translate(-1%, 4%)" },
          "60%": { transform: "translate(4%, -1%)" },
          "70%": { transform: "translate(-4%, -2%)" },
          "80%": { transform: "translate(2%, 3%)" },
          "90%": { transform: "translate(-2%, -3%)" },
        },
        "ticker": {
          "0%": { transform: "translateX(0)" },
          "100%": { transform: "translateX(-100%)" },
        },
        // Prompt typography motion — fade-in / fade-up
        "fade-in": {
          "0%": { opacity: "0", transform: "translateY(20px)" },
          "100%": { opacity: "1", transform: "translateY(0)" },
        },
        "fade-up": {
          "0%": { opacity: "0", transform: "translateY(40px)" },
          "100%": { opacity: "1", transform: "translateY(0)" },
        },
      },
      animation: {
        "blob-drift": "blob-drift 22s ease-in-out infinite",
        "float-slow": "float-slow 7s ease-in-out infinite",
        "marquee": "marquee 38s linear infinite",
        "ember-breathe": "ember-breathe 3.2s ease-in-out infinite",
        "wave-bar": "wave-bar 1.1s ease-in-out infinite",
        "aurora": "aurora 14s ease infinite",
        "shimmer": "shimmer 2.2s linear infinite",
        "progress-scan": "progress-scan 2.4s ease-in-out infinite",
        "pulse-ring": "pulse-ring 2.4s cubic-bezier(0.22, 1, 0.36, 1) infinite",
        "grain": "grain 8s steps(10) infinite",
        "fade-in": "fade-in 0.8s cubic-bezier(0.22, 1, 0.36, 1) both",
        "fade-up": "fade-up 0.7s cubic-bezier(0.22, 1, 0.36, 1) both",
      },
    },
  },
  plugins: [],
};

export default config;