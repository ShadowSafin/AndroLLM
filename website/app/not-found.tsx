import Link from "next/link";
import { Compass } from "lucide-react";
import { SectionHeading } from "@/components/marketing/section-heading";
import { site } from "@/lib/site";

export default function NotFound() {
  return (
    <section className="container flex min-h-[70vh] flex-col items-center justify-center py-28 text-center">
      <p className="font-geist inline-flex items-center justify-center gap-2 rounded-3xl border-[2px] border-gray-300/20 bg-gradient-to-tr from-zinc-300/5 via-gray-400/5 to-transparent px-5 py-2 text-sm tracking-tight uppercase text-gray-600 dark:border-white/5 dark:text-gray-400">404 — page not found</p>
      <h1 className="mt-5 max-w-2xl text-balance bg-gradient-to-br from-black from-30% to-black/40 bg-clip-text py-1 font-geist text-4xl font-semibold leading-none tracking-tighter text-transparent sm:text-4xl md:text-5xl dark:from-white dark:to-white/40">
        This page drifted off the ledger.
      </h1>
      <p className="mt-5 max-w-xl font-geist text-sm tracking-tight leading-relaxed text-gray-600 dark:text-gray-400">
        The address you followed doesn&rsquo;t exist here. It may have moved, been renamed, or never been written.
        The rest of the site is exactly where you left it.
      </p>
      <div className="mt-8 flex flex-wrap justify-center gap-3">
        <Link href="/" className="btn btn-primary font-geist tracking-tighter">
          <Compass className="size-4" aria-hidden /> Back to the home page
        </Link>
        <Link href="/docs" className="btn btn-ghost font-geist tracking-tighter">Browse documentation</Link>
        <a
          href={`${site.repo}/issues`}
          target="_blank"
          rel="noreferrer"
          className="btn btn-ghost font-geist tracking-tighter"
        >
          Report a broken link
        </a>
      </div>
    </section>
  );
}