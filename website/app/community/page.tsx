import type { Metadata } from "next";
import Link from "next/link";
import { MessageSquareText, Bug, BookOpen, GitFork, Heart, HandHeart } from "lucide-react";
import { SectionHeading } from "@/components/marketing/section-heading";
import { Reveal } from "@/animations/reveal";
import { WordByWord } from "@/components/motion/word-by-word";
import { CursorGlow } from "@/components/motion/cursor-glow";
import { Magnetic } from "@/components/motion/magnetic";
import { HoverCard } from "@/components/motion/accordion";
import { site } from "@/lib/site";

export const metadata: Metadata = {
  title: "Community — AndroLLM",
  description:
    "Join the AndroLLM community: GitHub Discussions for questions and ideas, Issues for bugs, and a contributing guide with a code of conduct.",
  alternates: { canonical: "/community" },
};

const channels = [
  {
    icon: MessageSquareText,
    title: "GitHub Discussions",
    text: "For questions, ideas, and showing off your setup. Start a thread, answer one, or vote on the roadmap.",
    cta: "Open Discussions →",
    href: site.discussions,
  },
  {
    icon: Bug,
    title: "Issue tracker",
    text: "Found a bug? File a report with device model, Android version, app version, and a step to reproduce.",
    cta: "Report an issue →",
    href: site.issues,
  },
  {
    icon: GitFork,
    title: "Pull requests",
    text: "The fastest way to shape the app. Branch, build, and open a PR — CI runs the checks for you.",
    cta: "Open a pull request →",
    href: `${site.repo}/pulls`,
  },
  {
    icon: BookOpen,
    title: "Documentation",
    text: "Forty-plus pages covering every layer of the stack: engine, app, voice, memory, and release process.",
    cta: "Browse the docs →",
    href: "/docs",
  },
];

const principles = [
  {
    title: "Be kind and constructive",
    text: "Everyone starts somewhere. Review code the way you'd want yours reviewed — specifics over snark.",
  },
  {
    title: "Respect every contributor",
    text: "Different setups, different devices, different skill levels. Help people bridge the gap.",
  },
  {
    title: "Follow the Code of Conduct",
    text: "The full expectations live in the repo. Harassment and trolling have a one-strike policy here.",
  },
];

export default function CommunityPage() {
  return (
    <>
      <section className="container py-28 md:py-36">
        <CursorGlow size={460} intensity={0.22} color="var(--accent)">
          <SectionHeading
            eyebrow="Community"
            title={<WordByWord text="No Discord. Just GitHub." />}
            description="AndroLLM keeps its community in the open: everything happens on GitHub — discussions, issues, pull requests. Public, searchable, and archived for anyone who comes after."
          />
        </CursorGlow>

        <div className="mt-14 grid gap-6 md:grid-cols-2">
          {channels.map((c, i) => (
            <Reveal key={c.title} delay={i * 0.07}>
              <Link
                href={c.href}
                target={c.href.startsWith("http") ? "_blank" : undefined}
                rel={c.href.startsWith("http") ? "noreferrer" : undefined}
                className="card group block h-full p-7"
              >
                <div className="flex items-start justify-between">
                  <c.icon className="size-4 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]" aria-hidden />
                  <span className="text-xs font-semibold uppercase tracking-[0.15em] text-[var(--faint)]">01</span>
                </div>
                <h2 className="mt-5 font-serif text-xl font-semibold text-[var(--ink)]">{c.title}</h2>
                <p className="mt-2 text-sm leading-relaxed text-[var(--muted)]">{c.text}</p>
                <p className="mt-4 text-sm font-bold text-[var(--accent-deep)] transition-transform duration-300 group-hover:translate-x-1 dark:text-[var(--accent-soft)]">
                  {c.cta}
                </p>
              </Link>
            </Reveal>
          ))}
        </div>

        <Reveal className="mt-14">
          <div className="card p-7">
            <div className="flex flex-wrap items-center justify-between gap-4">
              <div>
                <h2 className="font-serif text-xl font-semibold text-[var(--ink)]">Looking for support?</h2>
                <p className="mt-1.5 text-sm text-[var(--muted)]">
                  Start with the support policy and troubleshooting docs — most questions are answered there already.
                </p>
              </div>
              <div className="flex gap-3">
                <Link href={`${site.repo}/blob/main/SUPPORT.md`} target="_blank" rel="noreferrer" className="btn btn-secondary">
                  Support policy
                </Link>
                <Link href="/docs/troubleshooting" className="btn btn-primary">
                  Troubleshooting
                </Link>
              </div>
            </div>
          </div>
        </Reveal>

        <div className="mt-20 grid items-start gap-12 lg:grid-cols-2">
          <Reveal>
            <div>
              <p className="ledger">Contributing</p>
              <h2 className="mt-3 font-serif text-3xl font-semibold text-[var(--ink)]">Start small. Ship real.</h2>
              <p className="mt-4 text-sm leading-relaxed text-[var(--muted)]">
                The contributing guide covers the whole loop: fork, clone, branch, build, and open a PR. Try an
                &ldquo;engine&rdquo; issue first — nothing beats watching your first token stream out of surrounding
                symbols you helped compile.
              </p>
              <ol className="mt-6 space-y-3">
                {[
                  "Fork the repository and clone your fork",
                  "Create a feature branch: feat/add-provider, fix/device-lost-recovery, docs/…",
                  "Install the toolchain (see BUILDING.md) and build the debug APK",
                  "Run the test suite, then open the pull request against main",
                ].map((step, i) => (
                  <li key={step} className="flex items-start gap-3 text-sm leading-relaxed text-[var(--ink-dim)]">
                    <span className="mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full bg-[var(--accent-soft)] font-mono text-[10px] font-bold text-[var(--accent-deep)] dark:bg-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
                      {i + 1}
                    </span>
                    {step}
                  </li>
                ))}
              </ol>
              <div className="mt-7 flex flex-wrap gap-3">
                <Magnetic strength={0.18}>
                  <Link href={`${site.repo}/blob/main/CONTRIBUTING.md`} target="_blank" rel="noreferrer" className="btn btn-primary">
                    <HandHeart className="size-4" aria-hidden /> Read CONTRIBUTING.md
                  </Link>
                </Magnetic>
                <Link href={`${site.repo}/blob/main/CODE_OF_CONDUCT.md`} target="_blank" rel="noreferrer" className="btn btn-ghost">
                  Code of conduct
                </Link>
              </div>
            </div>
          </Reveal>

          <Reveal delay={0.1}>
            <div>
              <p className="ledger">Community principles</p>
              <div className="mt-6 space-y-4">
                {principles.map((p) => (
                  <HoverCard key={p.title} className="flex items-start gap-4 p-6">
                    <Heart className="mt-0.5 size-4 shrink-0 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]" aria-hidden />
                    <div>
                      <h3 className="text-sm font-semibold text-[var(--ink)]">{p.title}</h3>
                      <p className="mt-1.5 text-sm leading-relaxed text-[var(--muted)]">{p.text}</p>
                    </div>
                  </HoverCard>
                ))}
              </div>
              <p className="mt-5 text-xs leading-relaxed text-[var(--faint)]">
                Security issues are handled privately — see SECURITY.md. Never file vulnerabilities in the public tracker.
              </p>
            </div>
          </Reveal>
        </div>
      </section>
    </>
  );
}