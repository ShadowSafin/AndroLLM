"use client";

import { useState, useEffect } from "react";
import { Moon, Sun } from "lucide-react";
import { useTheme } from "next-themes";

export function ThemeToggle() {
  const { resolvedTheme, setTheme } = useTheme();
  const [mounted, setMounted] = useState(false);

  useEffect(() => setMounted(true), []);

  if (!mounted) {
    return (
      <button
        aria-label="Toggle theme"
        className="inline-flex size-10 items-center justify-center rounded-full border border-[var(--line)] bg-[var(--surface)] text-[var(--muted)] shadow-card"
      >
        <span className="size-4" />
      </button>
    );
  }

  const dark = resolvedTheme === "dark";

  return (
    <button
      onClick={() => setTheme(dark ? "light" : "dark")}
      aria-label={dark ? "Switch to light theme" : "Switch to dark theme"}
      className="inline-flex size-10 items-center justify-center rounded-full border border-[var(--line)] bg-[var(--surface)] text-[var(--muted)] shadow-card transition-all duration-300 hover:border-[var(--accent)] hover:text-[var(--accent-deep)] active:scale-95"
    >
      {dark ? <Sun className="size-4" /> : <Moon className="size-4" />}
    </button>
  );
}