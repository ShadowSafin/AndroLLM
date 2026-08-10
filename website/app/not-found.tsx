import Link from "next/link";
import { Compass } from "lucide-react";
import { SectionHeading } from "@/components/marketing/section-heading";
import { site } from "@/lib/site";

export default function NotFound() {
  return (
    <section className="container flex min-h-[70vh] flex-col items-center justify-center py-28 text-center">
      <p className="ledger">404 — page not found</p>
      <h1 className="mt-5 max-w-2xl font-serif text-4xl font-semibold leading-tight text-[var(--ink)] md:text-5xl">
        This page drifted off the ledger.
      </h1>
      <p className="mt-5 max-w-xl text-sm leading-relaxed text-[var(--muted)]">
        The address you followed doesn&rsquo;t exist here. It may have moved, been renamed, or never been written.
        The rest of the site is exactly where you left it.
      </p>
      <div className="mt-8 flex flex-wrap justify-center gap-3">
        <Link href="/" className="btn btn-primary">
          <Compass className="size-4" aria-hidden /> Back to the home page
        </Link>
        <Link href="/docs" className="btn btn-ghost">Browse documentation</Link>
        <a
          href={`${site.repo}/issues`}
          target="_blank"
          rel="noreferrer"
          className="btn btn-ghost"
        >
          Report a broken link
        </a>
      </div>
    </section>
  );
}