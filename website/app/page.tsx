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

export const metadata: Metadata = {
  title: "AndroLLM — Private AI. Native Android. Your Models. Your Choice.",
  description:
    "A production-grade AI platform for Android. Local GGUF inference on a vendored llama.cpp engine with Vulkan GPU acceleration, an offline voice assistant, an on-device agent with 50+ tools, persistent memory, and local image generation.",
  alternates: { canonical: "/" },
};

export default function Home() {
  return (
    <>
      <Hero />
      <TechMarquee />
      <FeatureGrid />
      <Showcase />
      <DetailSections />
      <Comparison />
      <Performance />
      <ModelsTeaser />
      <Providers />
      <PrivacyBand />
      <CtaBand />
    </>
  );
}