import Link from "next/link";
import { HomeIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyTitle,
} from "@/components/ui/empty";
import { site } from "@/lib/site";

// The oversized, bottom-faded 404 and the Empty primitives come from the
// upstream `not-found-2` component; the headline, description, and three CTAs
// are this page's own copy, which says more than the upstream placeholder did.
//
// The CTAs were styled with `.btn`, `.btn-primary`, and `.btn-ghost` — classes
// that exist nowhere in globals.css, so all three links rendered unstyled. They
// now use the project's Button with the same intent the class names expressed:
// primary (the default variant) for the main action, ghost for the two
// secondary ones.
//
// `mask-b-from-20%` / `mask-b-to-80%` upstream are Tailwind v4 utilities and
// are silently dropped by this project's Tailwind 3.4, so the mask is written
// as a v3 arbitrary property, plus the -webkit- prefix for Safari.
//
// `SectionHeading` was imported but never used; it is gone.

export default function NotFound() {
  return (
    <section className="container flex min-h-[70vh] items-center justify-center py-28 text-center">
      <Empty>
        <EmptyHeader className="max-w-2xl">
          <EmptyTitle className="font-geist text-9xl font-extrabold leading-none tracking-tighter [mask-image:linear-gradient(to_bottom,black_20%,transparent_80%)] [-webkit-mask-image:linear-gradient(to_bottom,black_20%,transparent_80%)]">
            404
          </EmptyTitle>
          <h1 className="text-gradient-prompt -mt-4 max-w-2xl font-geist text-3xl tracking-tighter sm:text-4xl md:text-5xl">
            This page drifted off the ledger.
          </h1>
        </EmptyHeader>

        <EmptyDescription className="mt-4 max-w-xl font-geist tracking-tight">
          The address you followed doesn&rsquo;t exist here. It may have moved, been renamed, or never been written.
          The rest of the site is exactly where you left it.
        </EmptyDescription>

        <EmptyContent className="max-w-2xl">
          <div className="flex flex-wrap justify-center gap-3">
            <Button asChild>
              <Link href="/">
                <HomeIcon className="size-4" aria-hidden />
                Back to the home page
              </Link>
            </Button>
            <Button asChild variant="ghost">
              <Link href="/docs">Browse documentation</Link>
            </Button>
            <Button asChild variant="ghost">
              <a href={`${site.repo}/issues`} target="_blank" rel="noreferrer">
                Report a broken link
              </a>
            </Button>
          </div>
        </EmptyContent>
      </Empty>
    </section>
  );
}