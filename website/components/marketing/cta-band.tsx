import Link from "next/link";
import { Download, Github } from "lucide-react";
import { Reveal } from "@/animations/reveal";
import { Button } from "@/components/ui/button";
import { site } from "@/lib/site";

export function CtaBand() {
  return (
    <section className="relative overflow-hidden py-28 sm:py-36" aria-label="Get started">
      <div
        className="absolute inset-0 -z-10"
        aria-hidden
        style={{
          background:
            "radial-gradient(48% 90% at 50% 0%, color-mix(in srgb, var(--accent) 12%, transparent), transparent 70%), radial-gradient(40% 70% at 70% 100%, color-mix(in srgb, var(--accent) 8%, transparent), transparent 70%)",
        }}
      />
      <div className="container">
        <Reveal className="mx-auto max-w-3xl text-center">
          <p className="font-geist inline-flex items-center justify-center gap-2 rounded-3xl border-[2px] border-gray-300/20 bg-gradient-to-tr from-zinc-300/5 via-gray-400/5 to-transparent px-5 py-2 text-sm tracking-tight uppercase text-gray-600 dark:border-white/5 dark:text-gray-400">
            <span className="inline-block size-1.5 rounded-full bg-[var(--accent)] animate-pulse" aria-hidden />
            v{site.version} is here
          </p>
          <h2 className="text-balance bg-gradient-to-br from-black from-30% to-black/40 bg-clip-text py-3 font-geist text-4xl font-semibold leading-none tracking-tighter text-transparent sm:text-5xl md:text-6xl dark:from-white dark:to-white/40">
            Your models. Your phone.
            <br />
            Your privacy.
          </h2>
          <p className="mt-5 text-balance text-lg tracking-tight text-gray-600 dark:text-gray-400 md:text-xl">
            Requires Android 9+ (API 28) and an ARM64 device. A fresh install ships with a curated catalog of
            21 models — the app tells you which ones your RAM can run.
          </p>
          <div className="mt-9 flex flex-wrap items-center justify-center gap-3">
            <Button asChild size="lg">
              <Link href="/downloads">
                <Download />
                Download the APK
              </Link>
            </Button>
            <Button asChild size="lg" variant="outline">
              <Link href={site.repo} target="_blank" rel="noreferrer">
                <Github />
                Read the source
              </Link>
            </Button>
          </div>
        </Reveal>
      </div>
    </section>
  );
}