"use client";

import Image from "next/image";
import { useState } from "react";
import { SectionHeading } from "@/components/marketing/section-heading";
import { Reveal } from "@/animations/reveal";
import { Lightbox } from "@/components/marketing/lightbox";
import { cn } from "@/lib/utils";

const shots = [
  { src: "/screenshots/chat.png", title: "On a real device", caption: "A live capture of the app on a Pixel — straight from the developer’s device." },
  { src: "/screenshots/voice-caption.png", title: "Voice overlay, captions live", caption: "The hands-free overlay — aurora glass, mascot orb, and the live transcript of your turn." },
  { src: "/screenshots/voice-caption-2.png", title: "Transcript while speaking", caption: "The spoken answer streams in the transcript block as Piper reads it aloud." },
  { src: "/screenshots/voice-check.png", title: "Spoken confirmation", caption: "Before anything that sends, pays, books or deletes — Andro asks, and listens for yes or no." },
  { src: "/screenshots/voice-karaoke.png", title: "NOW SPEAKING karaoke", caption: "Word-by-word highlight of the sentence being spoken — the current word glows in an accent pill." },
];

export function Screenshots() {
  const [active, setActive] = useState<number | null>(null);

  return (
    <section className="border-t border-[var(--line)] bg-[var(--deep)] py-24 sm:py-32" aria-label="Screenshots">
      <div className="container">
        <SectionHeading
          eyebrow="From the app"
          title="Straight from the device."
          description="Five real captures of the running app — no concept art, no renders."
        />
        <div className="mt-14 grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {shots.map((s, i) => (
            <Reveal key={s.src} delay={i * 0.05} className={cn(i === 0 && "sm:col-span-2 lg:col-span-2")}>
              <button
                type="button"
                onClick={() => setActive(i)}
                className="group flex w-full flex-col overflow-hidden rounded-card border border-[var(--line)] bg-[var(--surface)] text-left shadow-card transition-all hover:-translate-y-1 hover:shadow-[0_18px_40px_-16px_rgba(20,20,19,0.35)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
                aria-label={`Open screenshot: ${s.title}`}
              >
                <div className="relative overflow-hidden">
                  <Image
                    src={s.src}
                    alt={s.caption}
                    width={1280}
                    height={800}
                    className="aspect-video w-full object-cover transition-transform duration-500 group-hover:scale-[1.03]"
                    sizes="(max-width: 640px) 100vw, (max-width: 1024px) 50vw, 33vw"
                  />
                  <span className="absolute inset-0 bg-gradient-to-t from-[rgba(20,20,19,0.25)] to-transparent opacity-0 transition-opacity group-hover:opacity-100" />
                </div>
                <div className="flex items-center justify-between gap-3 px-5 py-4">
                  <div>
                    <p className="font-serif text-base font-semibold text-[var(--ink)]">{s.title}</p>
                    <p className="mt-0.5 text-xs leading-relaxed text-[var(--muted)]">{s.caption}</p>
                  </div>
                  <span className="shrink-0 rounded-pill border border-[var(--line)] px-2.5 py-1 font-mono text-[9px] uppercase tracking-widest text-[var(--faint)] transition-colors group-hover:border-[var(--accent)] group-hover:text-[var(--accent-deep)] dark:group-hover:text-[var(--accent-soft)]">
                    view
                  </span>
                </div>
              </button>
            </Reveal>
          ))}
          <Reveal delay={0.25}>
            <div className="flex h-full min-h-40 flex-col items-center justify-center gap-2 rounded-card border border-dashed border-[var(--line)] bg-[var(--surface)] p-6 text-center">
              <p className="font-serif text-base font-semibold text-[var(--ink)]">More where that came from</p>
              <p className="max-w-[20rem] text-xs leading-relaxed text-[var(--muted)]">
                Voice, agent, image generation, and model management screens — covered in the documentation’s app guide.
              </p>
            </div>
          </Reveal>
        </div>
      </div>

      {active !== null && (
        <Lightbox
          src={shots[active].src}
          alt={`${shots[active].title} — ${shots[active].caption}`}
          open
          onClose={() => setActive(null)}
        />
      )}
    </section>
  );
}