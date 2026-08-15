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
        parchment: {
          canvas: "#F5F4ED",
          raised: "#ECEBE3",
          deep: "#EFEEE6",
          surface: "#FBFAF4",
          elevated: "#FFFFFF",
          border: "#E8E6DC",
          borderSoft: "#F0EEE6",
        },
        ink: {
          DEFAULT: "#141413",
          dim: "#4A4945",
          muted: "#5E5D59",
          faint: "#8F8D87",
        },
        ember: {
          DEFAULT: "#D97757",
          light: "#E69D81",
          deep: "#B3573E",
          halo: "#40D97757",
        },
        lamp: {
          DEFAULT: "#C78871",
        },
        night: {
          canvas: "#141414",
          surface: "#1C1C1B",
          raised: "#272727",
          border: "#2A2A28",
          borderSoft: "#232323",
        },
        ok: { DEFAULT: "#52C41A" },
        warn: { DEFAULT: "#E0A33D" },
        err: { DEFAULT: "#C7442F" },
      },
      fontFamily: {
        sans: ["var(--font-public-sans)", ...defaultTheme.fontFamily.sans],
        serif: ["var(--font-fraunces)", ...defaultTheme.fontFamily.serif],
        mono: ["var(--font-jetbrains)", ...defaultTheme.fontFamily.mono],
      },
      fontSize: {
        "display-xl": ["clamp(2.75rem, 6vw, 5.5rem)", { lineHeight: "1.02", letterSpacing: "-0.03em" }],
        "display-lg": ["clamp(2.25rem, 4.5vw, 3.75rem)", { lineHeight: "1.05", letterSpacing: "-0.025em" }],
        "display-md": ["clamp(1.75rem, 3.2vw, 2.5rem)", { lineHeight: "1.12", letterSpacing: "-0.02em" }],
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
        "ember-glow": "radial-gradient(1200px 600px at 50% -10%, rgba(217,119,87,0.16), transparent 60%)",
        "grid-parchment":
          "linear-gradient(to right, rgba(20,20,19,0.045) 1px, transparent 1px), linear-gradient(to bottom, rgba(20,20,19,0.045) 1px, transparent 1px)",
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
        "fade-up": {
          "0%": { opacity: "0", transform: "translateY(12px)" },
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
        "fade-up": "fade-up 0.7s cubic-bezier(0.22, 1, 0.36, 1) both",
      },
    },
  },
  plugins: [],
};

export default config;