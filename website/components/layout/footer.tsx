import Link from "next/link";
import { Github, MessageCircle, Bug, Heart, Code2 } from "lucide-react";
import { site } from "@/lib/site";
import { Logo } from "@/components/logo";
import { AnimatedUnderline } from "@/components/motion/animated-underline";
import { Magnetic } from "@/components/motion/magnetic";
import { LiveDot } from "@/components/motion/live-ticker";

const columns = [
  {
    title: "Product",
    links: [
      { label: "Features", href: "/features" },
      { label: "Models", href: "/models" },
      { label: "Downloads", href: "/downloads" },
      { label: "Roadmap", href: "/roadmap" },
      { label: "Changelog", href: "/changelog" },
    ],
  },
  {
    title: "Resources",
    links: [
      { label: "Documentation", href: "/docs" },
      { label: "First run guide", href: "/docs/getting-started/first-run" },
      { label: "Model support", href: "/docs/model-support" },
      { label: "Building from source", href: "/docs/building" },
      { label: "FAQ", href: "/docs/faq" },
      { label: "Blog", href: "/blog" },
    ],
  },
  {
    title: "Community",
    links: [
      { label: "Community hub", href: "/community" },
      { label: "GitHub Discussions", href: site.discussions },
      { label: "GitHub Issues", href: site.issues },
      { label: "Contributors", href: "/contributors" },
      { label: "GitHub repository", href: site.repo },
    ],
  },
  {
    title: "Legal",
    links: [
      { label: "Privacy policy", href: "/privacy" },
      { label: "License — Apache 2.0", href: "/license" },
      { label: "Security policy", href: "/community#security" },
      { label: "About", href: "/about" },
    ],
  },
];

export function Footer() {
  return (
    <footer className="relative border-t border-[var(--line)] bg-[var(--deep)]">
      <div className="container py-16">
        <div className="grid gap-12 lg:grid-cols-[1.4fr_2fr]">
          <div>
            <Logo />
            <p className="mt-5 max-w-sm text-sm leading-relaxed text-[var(--muted)]">
              {site.description}
            </p>
            <p className="mt-4 text-sm text-[var(--faint)]">
              Zero cloud dependency. Zero data leaves your phone — unless you choose otherwise.
            </p>
            <div className="mt-6 flex flex-wrap items-center gap-3">
              <Magnetic strength={0.25}>
              <a
                href={site.repo}
                target="_blank"
                rel="noreferrer"
                aria-label="GitHub"
                className="inline-flex size-10 items-center justify-center rounded-full border border-[var(--line)] bg-[var(--surface)] text-[var(--muted)] shadow-card transition-all hover:border-[var(--accent)] hover:text-[var(--accent-deep)]"
              >
                <Github className="size-4" />
              </a>
            </Magnetic>
            <Magnetic strength={0.25}>
              <a
                href={site.discussions}
                target="_blank"
                rel="noreferrer"
                aria-label="Community discussions"
                className="inline-flex size-10 items-center justify-center rounded-full border border-[var(--line)] bg-[var(--surface)] text-[var(--muted)] shadow-card transition-all hover:border-[var(--accent)] hover:text-[var(--accent-deep)]"
              >
                <MessageCircle className="size-4" />
              </a>
            </Magnetic>
            <Magnetic strength={0.25}>
              <a
                href={site.issues}
                target="_blank"
                rel="noreferrer"
                aria-label="Report an issue"
                className="inline-flex size-10 items-center justify-center rounded-full border border-[var(--line)] bg-[var(--surface)] text-[var(--muted)] shadow-card transition-all hover:border-[var(--accent)] hover:text-[var(--accent-deep)]"
              >
                <Bug className="size-4" />
              </a>
            </Magnetic>
              <a
                href={site.repo}
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center gap-2 rounded-full border border-[var(--line)] bg-[var(--surface)] px-4 py-2 text-xs font-semibold text-[var(--muted)] shadow-card transition-all hover:border-[var(--accent)] hover:text-[var(--accent-deep)]"
              >
                <LiveDot />
                <Code2 className="size-3.5" />
                v{site.version} · Apache 2.0
              </a>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-8 sm:grid-cols-4">
            {columns.map((col) => (
              <nav key={col.title} aria-label={col.title}>
                <h3 className="ledger text-[var(--faint)]">{col.title}</h3>
                <ul className="mt-4 space-y-2.5">
                  {col.links.map((link) => (
                    <li key={link.label}>
                      <AnimatedUnderline>
                        <Link
                          href={link.href}
                          target={link.href.startsWith("http") ? "_blank" : undefined}
                          rel={link.href.startsWith("http") ? "noreferrer" : undefined}
                          className="text-sm text-[var(--muted)] transition-colors hover:text-[var(--accent-deep)] dark:hover:text-[var(--accent-soft)]"
                        >
                          {link.label}
                        </Link>
                      </AnimatedUnderline>
                    </li>
                  ))}
                </ul>
              </nav>
            ))}
          </div>
        </div>

        <div className="mt-14 flex flex-col items-start justify-between gap-4 border-t border-[var(--line)] pt-8 text-xs text-[var(--faint)] sm:flex-row sm:items-center">
          <p>
            © {new Date().getFullYear()} AndroLLM · Licensed under Apache License 2.0. Third-party licenses in{" "}
            <a href={site.license} target="_blank" rel="noreferrer" className="underline underline-offset-2 hover:text-[var(--accent-deep)]">
              LICENSES.md
            </a>
            .
          </p>
          <p className="inline-flex items-center gap-1.5">
            Crafted with <Heart className="size-3 text-[var(--accent)]" aria-hidden /> by the AndroLLM community
          </p>
        </div>
      </div>
    </footer>
  );
}