"use client";

import * as React from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { AnimatePresence, motion, useScroll } from "framer-motion";
import {
  Menu,
  Github,
  MessageCircle,
  Download,
  X,
  Cpu,
  Mic,
  Bot,
  BrainCircuit,
  Cloud,
  Database,
  BookOpen,
  Rocket,
  FileText,
  Sparkles,
  Users,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { navigation, site } from "@/lib/site";
import { Logo } from "@/components/logo";
import { Button } from "@/components/ui/button";
import {
  MotionNavigationMenu,
  MotionNavigationMenuContent,
  MotionNavigationMenuItem,
  MotionNavigationMenuLink,
  MotionNavigationMenuList,
  MotionNavigationMenuTrigger,
} from "@/components/ui/motion-navigation-menu";

const highlightClassName = "bg-white/[0.07] rounded-full";
const contentHighlight = "bg-white/[0.06] rounded-lg";
const viewportClassName =
  "bg-black/95 backdrop-blur-xl border border-white/10 rounded-xl shadow-[0_24px_64px_rgba(0,0,0,0.8)]";

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
        scrolled
          ? "glass border-b border-white/10 bg-black/70 shadow-[0_8px_32px_rgba(0,0,0,0.6)]"
          : "bg-transparent border-b border-transparent"
      )}
    >
      <nav
        aria-label="Primary"
        className="container flex h-16 items-center justify-between gap-4 md:h-[4.5rem]"
      >
        <Logo />

        {/* Desktop — motion navigation */}
        <div className="hidden lg:flex items-center">
          <MotionNavigationMenu
            viewport
            viewportClassName={viewportClassName}
            className="bg-transparent"
          >
            <MotionNavigationMenuList
              highlightClassName={highlightClassName}
              className="gap-0.5"
            >
              {/* Features */}
              <MotionNavigationMenuItem value="features">
                <MotionNavigationMenuTrigger className="rounded-full px-3.5 py-2 font-geist text-sm font-medium tracking-tight text-white/70 hover:text-white data-[state=open]:bg-white/10 data-[state=open]:text-white">
                  Features
                </MotionNavigationMenuTrigger>
                <MotionNavigationMenuContent highlightClassName={contentHighlight}>
                  <div className="grid w-[580px] grid-cols-[1.05fr_1.4fr] gap-3">
                    <MotionNavigationMenuLink
                      href="/features"
                      className="group relative flex min-h-[200px] flex-col justify-between overflow-hidden rounded-xl border border-white/10 bg-gradient-to-br from-white/[0.08] to-white/[0.02] p-5 hover:border-white/15"
                    >
                      <span className="flex size-9 items-center justify-center rounded-lg bg-white text-black">
                        <Cpu className="size-4" />
                      </span>
                      <span className="space-y-2">
                        <span className="block font-geist text-sm font-semibold tracking-tight text-white">
                          Explore features
                        </span>
                        <span className="block font-geist text-xs leading-relaxed tracking-tight text-white/55">
                          Local inference, GPU accel, voice, agent, memory — 9 pillars on-device.
                        </span>
                      </span>
                    </MotionNavigationMenuLink>

                    <div className="grid grid-cols-1 gap-1">
                      {[
                        { name: "Local Engine", desc: "LiteRT-LM .litertlm", icon: Cpu, href: "/features#local-engine" },
                        { name: "Voice Assistant", desc: "Hey Andro, offline", icon: Mic, href: "/features#voice" },
                        { name: "Agent Platform", desc: "50+ tools, safety-gated", icon: Bot, href: "/features#agent" },
                        { name: "Persistent Memory", desc: "Vector + hybrid search", icon: BrainCircuit, href: "/features#memory" },
                      ].map((f) => (
                        <MotionNavigationMenuLink
                          key={f.name}
                          href={f.href}
                          className="group flex items-center gap-3 rounded-xl px-3 py-2.5 hover:bg-white/[0.04]"
                        >
                          <span className="flex size-8 items-center justify-center rounded-lg border border-white/10 bg-white/[0.04] text-white/80 group-hover:bg-white group-hover:text-black transition-colors">
                            <f.icon className="size-4" />
                          </span>
                          <span className="space-y-0.5">
                            <span className="block font-geist text-sm font-medium tracking-tight text-white">
                              {f.name}
                            </span>
                            <span className="block font-geist text-xs tracking-tight text-white/45">
                              {f.desc}
                            </span>
                          </span>
                        </MotionNavigationMenuLink>
                      ))}
                    </div>
                  </div>
                </MotionNavigationMenuContent>
              </MotionNavigationMenuItem>

              {/* Models */}
              <MotionNavigationMenuItem value="models">
                <MotionNavigationMenuTrigger className="rounded-full px-3.5 py-2 font-geist text-sm font-medium tracking-tight text-white/70 hover:text-white data-[state=open]:bg-white/10 data-[state=open]:text-white">
                  Models
                </MotionNavigationMenuTrigger>
                <MotionNavigationMenuContent highlightClassName={contentHighlight}>
                  <div className="w-[520px] space-y-3 p-1">
                    <div className="grid grid-cols-2 gap-2">
                      {[
                        { title: "21 curated .litertlm", desc: "Qwen · Gemma · DeepSeek", href: "/models#catalog" },
                        { title: "Cloud via LiteLLM", desc: "Gemini · Claude · GPT", href: "/models#cloud" },
                        { title: "HuggingFace", desc: "Browse & download", href: "/models#huggingface" },
                        { title: "Benchmarks", desc: "Tokens/sec · TTFT", href: "/models#benchmarks" },
                      ].map((m) => (
                        <MotionNavigationMenuLink
                          key={m.title}
                          href={m.href}
                          className="rounded-xl border border-white/5 bg-white/[0.02] p-3 hover:border-white/10 hover:bg-white/[0.05]"
                        >
                          <span className="block font-geist text-sm font-medium tracking-tight text-white">
                            {m.title}
                          </span>
                          <span className="mt-1 block font-geist text-xs tracking-tight text-white/45">
                            {m.desc}
                          </span>
                        </MotionNavigationMenuLink>
                      ))}
                    </div>
                    <MotionNavigationMenuLink
                      href="/models"
                      className="flex items-center justify-between rounded-xl bg-white text-black p-3 hover:bg-zinc-100"
                    >
                      <span className="flex items-center gap-2 font-geist text-sm font-semibold tracking-tight">
                        <Database className="size-4" /> Model catalog
                      </span>
                      <span className="font-geist text-xs tracking-tight text-black/60">Open →</span>
                    </MotionNavigationMenuLink>
                  </div>
                </MotionNavigationMenuContent>
              </MotionNavigationMenuItem>

              {/* Resources */}
              <MotionNavigationMenuItem value="resources">
                <MotionNavigationMenuTrigger className="rounded-full px-3.5 py-2 font-geist text-sm font-medium tracking-tight text-white/70 hover:text-white data-[state=open]:bg-white/10 data-[state=open]:text-white">
                  Resources
                </MotionNavigationMenuTrigger>
                <MotionNavigationMenuContent highlightClassName={contentHighlight}>
                  <div className="grid w-[560px] grid-cols-[1.2fr_1fr] gap-3 p-1">
                    <div className="space-y-1">
                      {[
                        { title: "Documentation", desc: "Guides & API ref", icon: BookOpen, href: "/docs" },
                        { title: "Roadmap", desc: "32 shipped · 7 in progress", icon: Rocket, href: "/roadmap" },
                        { title: "Changelog", desc: "What shipped", icon: FileText, href: "/changelog" },
                      ].map((r) => (
                        <MotionNavigationMenuLink
                          key={r.title}
                          href={r.href}
                          className="flex items-center gap-3 rounded-xl px-3 py-2.5 hover:bg-white/[0.04]"
                        >
                          <span className="flex size-8 items-center justify-center rounded-lg bg-white/[0.06] text-white/70">
                            <r.icon className="size-4" />
                          </span>
                          <span>
                            <span className="block font-geist text-sm font-medium tracking-tight text-white">
                              {r.title}
                            </span>
                            <span className="block font-geist text-xs tracking-tight text-white/45">
                              {r.desc}
                            </span>
                          </span>
                        </MotionNavigationMenuLink>
                      ))}
                    </div>

                    <MotionNavigationMenuLink
                      href="/community"
                      className="flex flex-col justify-between rounded-xl border border-white/10 bg-white/[0.04] p-4 hover:border-white/15 hover:bg-white/[0.06]"
                    >
                      <span className="flex items-center gap-2 font-geist text-sm font-medium tracking-tight text-white">
                        <Users className="size-4" /> Community
                      </span>
                      <span className="font-geist text-xs leading-relaxed tracking-tight text-white/55">
                        GitHub discussions, issues, and roadmap votes — join the ledger.
                      </span>
                      <span className="font-geist text-xs font-medium tracking-tight text-white/70">Join →</span>
                    </MotionNavigationMenuLink>
                  </div>
                </MotionNavigationMenuContent>
              </MotionNavigationMenuItem>

              {/* Single links — no dropdown */}
              <MotionNavigationMenuItem>
                <MotionNavigationMenuLink
                  href="/roadmap"
                  className="flex h-9 items-center rounded-full px-3.5 py-2 font-geist text-sm font-medium tracking-tight text-white/70 hover:text-white hover:bg-white/10 data-[active=true]:bg-white/10 data-[active=true]:text-white"
                >
                  Roadmap
                </MotionNavigationMenuLink>
              </MotionNavigationMenuItem>

              <MotionNavigationMenuItem>
                <MotionNavigationMenuLink
                  href="/changelog"
                  className="flex h-9 items-center rounded-full px-3.5 py-2 font-geist text-sm font-medium tracking-tight text-white/70 hover:text-white hover:bg-white/10"
                >
                  Changelog
                </MotionNavigationMenuLink>
              </MotionNavigationMenuItem>

              <MotionNavigationMenuItem>
                <MotionNavigationMenuLink
                  href="/docs"
                  className="flex h-9 items-center rounded-full px-3.5 py-2 font-geist text-sm font-medium tracking-tight text-white/70 hover:text-white hover:bg-white/10"
                >
                  Docs
                </MotionNavigationMenuLink>
              </MotionNavigationMenuItem>
            </MotionNavigationMenuList>
          </MotionNavigationMenu>
        </div>

        {/* Fallback for tablet: keep minimal flat nav */}
        <div className="hidden items-center gap-0.5 md:flex lg:hidden">
          {navigation.slice(0, 4).map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className="rounded-full px-3 py-2 font-geist text-sm font-medium tracking-tight text-white/60 hover:text-white"
            >
              {item.label}
            </Link>
          ))}
        </div>

        <div className="flex items-center gap-2.5">
          <a
            href={site.repo}
            target="_blank"
            rel="noreferrer"
            aria-label="AndroLLM on GitHub"
            className="hidden size-10 items-center justify-center rounded-full border border-white/10 bg-white/[0.04] text-white/60 backdrop-blur transition-all hover:border-white/20 hover:text-white sm:inline-flex"
          >
            <Github className="size-4" />
          </a>
          <Button asChild size="sm" className="hidden sm:inline-flex rounded-full bg-white text-black hover:bg-zinc-100">
            <Link href="/downloads">
              <Download />
              Download
            </Link>
          </Button>
          <Button
            variant="ghost"
            size="icon"
            className="lg:hidden rounded-full border border-white/10 bg-white/5 text-white hover:bg-white/10 hover:text-white"
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
            className="border-t border-white/10 bg-black/95 backdrop-blur-xl lg:hidden"
          >
            <div className="container flex flex-col gap-1 py-5">
              {navigation.map((item) => (
                <Link
                  key={item.href}
                  href={item.href}
                  className={cn(
                    "rounded-xl px-4 py-3 font-geist text-base font-medium tracking-tight transition-colors",
                    pathname.startsWith(item.href)
                      ? "bg-white text-black"
                      : "text-white/60 hover:bg-white/10 hover:text-white"
                  )}
                >
                  {item.label}
                </Link>
              ))}
              <div className="mt-3 flex gap-3">
                <Button asChild className="flex-1 rounded-full bg-white text-black hover:bg-zinc-100">
                  <Link href="/downloads">
                    <Download />
                    Download APK
                  </Link>
                </Button>
                <Button
                  asChild
                  variant="outline"
                  className="flex-1 rounded-full border-white/15 bg-transparent text-white hover:bg-white/10 hover:text-white"
                >
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
