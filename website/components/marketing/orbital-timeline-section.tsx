"use client";

import { Calendar, Code, FileText, User, Clock } from "lucide-react";
import { SectionHeading } from "@/components/marketing/section-heading";
import RadialOrbitalTimeline from "@/components/ui/radial-orbital-timeline";

const orbitalTimelineData = [
  {
    id: 1,
    title: "Genesis",
    date: "Q4 2025",
    content: "Multi-module Gradle, Compose Material3, Hilt + Room + DataStore — foundation laid, ledger opened.",
    category: "Foundation",
    icon: Calendar,
    relatedIds: [2],
    status: "completed" as const,
    energy: 100,
  },
  {
    id: 2,
    title: "Core Engine",
    date: "Q1 2026",
    content: "LiteRT-LM 0.16.0 pure-Kotlin runtime, OpenCL GPU delegate, .litertlm validation, 21 curated models.",
    category: "Engine",
    icon: FileText,
    relatedIds: [1, 3],
    status: "completed" as const,
    energy: 92,
  },
  {
    id: 3,
    title: "Intelligence",
    date: "Mar 2026",
    content: "Vector memory, LiteLLM multi-provider router, 50+ agent tools, SSE streaming — intelligence on-device.",
    category: "Intelligence",
    icon: Code,
    relatedIds: [2, 4],
    status: "in-progress" as const,
    energy: 70,
  },
  {
    id: 4,
    title: "Voice",
    date: "Apr 2026",
    content: "Sherpa-ONNX wake-word → ASR → TTS, foreground service, overlay, barge-in — hands-free offline.",
    category: "Voice",
    icon: User,
    relatedIds: [3, 5],
    status: "in-progress" as const,
    energy: 55,
  },
  {
    id: 5,
    title: "Release",
    date: "May 2026",
    content: "Play Store, CI/CD, benchmark dashboard, NPU next — private AI for every Android 9+ device.",
    category: "Release",
    icon: Clock,
    relatedIds: [4],
    status: "pending" as const,
    energy: 22,
  },
];

export function OrbitalTimelineSection() {
  return (
    <section className="relative bg-black py-24 sm:py-32" aria-label="Orbital timeline">
      <div className="container">
        <SectionHeading
          eyebrow="Orbital Timeline"
          title="Five orbits from idea to on-device."
          description="Tap a node to expand — related phases pulse, orbit centers, auto-rotate pauses. Pure black/white, Geist tight tracking, balanced type."
        />
      </div>
      <div className="mt-12 sm:mt-16">
        <RadialOrbitalTimeline timelineData={orbitalTimelineData} />
      </div>
      <p className="container mt-6 text-center font-geist text-xs tracking-tight text-white/40">
        Orbital view · click background to reset · auto-rotates when idle
      </p>
    </section>
  );
}
