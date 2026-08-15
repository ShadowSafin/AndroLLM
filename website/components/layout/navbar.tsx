"use client";

import * as React from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { AnimatePresence, motion, useScroll } from "framer-motion";
import { Menu, Github, MessageCircle, Download, X } from "lucide-react";
import { cn } from "@/lib/utils";
import { navigation, site } from "@/lib/site";
import { Logo } from "@/components/logo";
import { ThemeToggle } from "@/components/theme-toggle";
import { Button } from "@/components/ui/button";

export function Navbar() {
  const pathname = usePathname();
  const { scrollY } = useScroll();
  const [scrolled, setScrolled] = React.useState(false);
  const [open, setOpen] = React.useState(false);

  React.useEffect(() => {
    const unsub = scrollY.on("change", (v) => setScrolled(v > 12));
    return () => unsub();
  }, [scrollY]);

  React.useEffect(() => setOpen(false), [pathname]);

  React.useEffect(() => {
    document.body.style.overflow = open ? "hidden" : "";
    return () => {
      document.body.style.overflow = "";
    };
  }, [open]);

  return (
    <header
      className={cn(
        "fixed inset-x-0 top-0 z-40 transition-all duration-500",
        scrolled ? "glass border-b border-[var(--line)] shadow-[var(--nav-shadow)]" : "bg-transparent"
      )}
    >
      <nav
        aria-label="Primary"
        className="container flex h-16 items-center justify-between gap-4 md:h-[4.5rem]"
      >
        <Logo />

        <div className="hidden items-center gap-0.5 lg:flex">
          {navigation.map((item) => {
            const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
            return (
              <Link
                key={item.href}
                href={item.href}
                className={cn(
                  "group relative rounded-pill px-3.5 py-2 text-sm font-medium transition-colors",
                  active ? "text-[var(--ink)]" : "text-[var(--muted)] hover:text-[var(--ink)]"
                )}
              >
                {active && (
                  <motion.span
                    layoutId="nav-pill"
                    className="absolute inset-0 -z-10 rounded-pill bg-[color-mix(in_srgb,var(--faint)_14%,transparent)]"
                    transition={{ type: "spring", bounce: 0.25, duration: 0.55 }}
                  />
                )}
                <span className="relative">
                  {item.label}
                  <span
                    aria-hidden
                    className={cn(
                      "pointer-events-none absolute -bottom-1 left-0 h-px w-full origin-left scale-x-0 bg-gradient-to-r from-[var(--accent)] to-transparent transition-transform duration-300 ease-out",
                      active ? "scale-x-100" : "group-hover:scale-x-100"
                    )}
                  />
                </span>
              </Link>
            );
          })}
        </div>

        <div className="flex items-center gap-2.5">
          <ThemeToggle />
          <a
            href={site.repo}
            target="_blank"
            rel="noreferrer"
            aria-label="AndroLLM on GitHub"
            className="hidden size-10 items-center justify-center rounded-full border border-[var(--line)] bg-[var(--surface)] text-[var(--muted)] shadow-card transition-all hover:border-[var(--accent)] hover:text-[var(--accent-deep)] sm:inline-flex"
          >
            <Github className="size-4" />
          </a>
          <Button asChild size="sm" className="hidden sm:inline-flex">
            <Link href="/downloads">
              <Download />
              Download
            </Link>
          </Button>
          <Button
            variant="ghost"
            size="icon"
            className="lg:hidden"
            onClick={() => setOpen((v) => !v)}
            aria-expanded={open}
            aria-label={open ? "Close menu" : "Open menu"}
          >
            {open ? <X className="size-5" /> : <Menu className="size-5" />}
          </Button>
        </div>
      </nav>

      <AnimatePresence>
        {open && (
          <motion.div
            initial={{ opacity: 0, y: -8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -8 }}
            transition={{ duration: 0.25, ease: [0.22, 1, 0.36, 1] }}
            className="glass border-t border-[var(--line)] lg:hidden"
          >
            <div className="container flex flex-col gap-1 py-5">
              {navigation.map((item) => (
                <Link
                  key={item.href}
                  href={item.href}
                  className={cn(
                    "rounded-card px-4 py-3 text-base font-medium transition-colors",
                    pathname.startsWith(item.href)
                      ? "bg-[color-mix(in_srgb,var(--accent)_10%,transparent)] text-[var(--ink)]"
                      : "text-[var(--muted)] hover:bg-[color-mix(in_srgb,var(--faint)_10%,transparent)] hover:text-[var(--ink)]"
                  )}
                >
                  {item.label}
                </Link>
              ))}
              <div className="mt-3 flex gap-3">
                <Button asChild className="flex-1">
                  <Link href="/downloads">
                    <Download />
                    Download APK
                  </Link>
                </Button>
                <Button asChild variant="secondary" className="flex-1">
                  <Link href={site.discussions} target="_blank" rel="noreferrer">
                    <MessageCircle />
                    Community
                  </Link>
                </Button>
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </header>
  );
}