import type { Metadata } from "next";
import { Hero } from "@/components/marketing/hero";
import { TechMarquee } from "@/components/marketing/tech-marquee";
import { FeatureGrid } from "@/components/marketing/feature-grid";
import { Showcase } from "@/components/marketing/showcase";
import { DetailSections } from "@/components/marketing/detail-sections";
import { Comparison } from "@/components/marketing/comparison";
import { Performance } from "@/components/marketing/performance";
import { ModelsTeaser } from "@/components/marketing/models-teaser";
import { Providers } from "@/components/marketing/providers";
import { PrivacyBand } from "@/components/marketing/privacy-band";
import { CtaBand } from "@/components/marketing/cta-band";
import { OrbitalTimelineSection } from "@/components/marketing/orbital-timeline-section";
import { SectionHeading } from "@/components/marketing/section-heading";
import DisplayCards from "@/components/ui/display-cards";
import { Cpu, Mic, ShieldCheck } from "lucide-react";

export const metadata: Metadata = {
  title: "AndroLLM — Private AI. Native Android. Your Models. Your Choice.",
  description:
    "A production-grade AI platform for Android. Local .litertlm inference on Google's LiteRT-LM engine with CPU and OpenCL-GPU acceleration, an offline voice assistant, an on-device agent with 50+ tools, and persistent memory.",
  alternates: { canonical: "/" },
};

const atAGlanceCards = [
  {
    icon: <Cpu className="size-4 text-white" />,
    title: "On-Device",
    description: "21 curated .litertlm models",
    date: "Q1 2026 · LiteRT-LM",
    iconClassName: "text-white",
    titleClassName: "text-white",
    className:
      "[grid-area:stack] hover:-translate-y-10 before:absolute before:w-[100%] before:outline-1 before:rounded-xl before:outline-border before:h-[100%] before:content-[''] before:bg-blend-overlay before:bg-background/50 grayscale-[100%] hover:before:opacity-0 before:transition-opacity before:duration-700 hover:grayscale-0 before:left-0 before:top-0",
  },
  {
    icon: <Mic className="size-4 text-white" />,
    title: "Offline Voice",
    description: "Hey Andro — fully offline",
    date: "Sherpa · Piper · 12 cmds",
    iconClassName: "text-white",
    titleClassName: "text-white",
    className:
      "[grid-area:stack] translate-x-12 translate-y-10 hover:-translate-y-1 before:absolute before:w-[100%] before:outline-1 before:rounded-xl before:outline-border before:h-[100%] before:content-[''] before:bg-blend-overlay before:bg-background/50 grayscale-[100%] hover:before:opacity-0 before:transition-opacity before:duration-700 hover:grayscale-0 before:left-0 before:top-0 sm:translate-x-14 sm:translate-y-12",
  },
  {
    icon: <ShieldCheck className="size-4 text-white" />,
    title: "Zero Telemetry",
    description: "No cloud unless you opt in",
    date: "Apache 2.0 · 0 trackers",
    iconClassName: "text-white",
    titleClassName: "text-white",
    className: "[grid-area:stack] translate-x-24 translate-y-20 hover:translate-y-10 sm:translate-x-28 sm:translate-y-24",
  },
];

export default function Home() {
  return (
    <>
      <Hero />
      <TechMarquee />
      <FeatureGrid />
      <Showcase />
      {/* DisplayCards — skewed stack, AndroLLM at-a-glance */}
      <section className="relative overflow-hidden bg-black py-12 sm:py-20" aria-label="At a glance">
        <div className="container">
          <SectionHeading
            eyebrow="At a glance"
            title="Three cards. Zero cloud."
            description="Skewed, stacked, hover to reveal — the core promises of AndroLLM in one glance. Grayscale by default, color on hover — private by design."
          />
          <div className="flex min-h-[460px] w-full items-center justify-center overflow-visible py-16 sm:py-20">
            <div className="w-full max-w-[560px] mx-auto flex justify-center">
              <div className="-translate-x-6 sm:-translate-x-7">
                <DisplayCards cards={atAGlanceCards} />
              </div>
            </div>
          </div>
        </div>
      </section>
      <DetailSections />
      <Comparison />
      <OrbitalTimelineSection />
      <Performance />
      <ModelsTeaser />
      <Providers />
      <PrivacyBand />
      <CtaBand />
    </>
  );
}