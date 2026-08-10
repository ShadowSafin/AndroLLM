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
            "radial-gradient(48% 90% at 50% 0%, rgba(217,119,87,0.12), transparent 70%), radial-gradient(40% 70% at 70% 100%, rgba(217,119,87,0.08), transparent 70%)",
        }}
      />
      <div className="container">
        <Reveal className="mx-auto max-w-3xl text-center">
          <p className="ledger inline-flex items-center gap-2 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
            <span className="inline-block size-1.5 rounded-full bg-[var(--accent)] animate-pulse" aria-hidden />
            v{site.version} is here
          </p>
          <h2 className="text-balance mt-5 font-serif text-4xl font-semibold leading-tight tracking-tight text-[var(--ink)] sm:text-5xl">
            Your models. Your phone.<br />
            <em className="text-gradient-ember not-italic">Your privacy.</em>
          </h2>
          <p className="mt-5 text-base leading-relaxed text-[var(--muted)] sm:text-lg">
            Requires Android 9+ (API 28) and an ARM64 device. A fresh install ships with a curated catalog of
            101 models — the app tells you which ones your RAM can run.
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