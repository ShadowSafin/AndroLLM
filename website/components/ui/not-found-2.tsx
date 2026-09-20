import Link from "next/link";
import { Button } from "@/components/ui/button";
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyTitle,
} from "@/components/ui/empty";
import { HomeIcon, CompassIcon } from "lucide-react";

// Adapted for this project from the upstream 21st.dev source. Changes:
//
//  1. `mask-b-from-20%` / `mask-b-to-80%` are Tailwind v4 mask utilities. This
//     project is on Tailwind 3.4, whose default theme has no `maskImage` scale,
//     so those classes are silently dropped and the "404" would render solid.
//     They are rewritten as arbitrary-property equivalents, which v3 does
//     support, plus the -webkit- prefix for Safari.
//  2. shadcn semantic tokens mapped to this project's LAYER 2 tokens:
//     `text-foreground/80` → `--muted`.
//  3. Both CTAs used `href="#"`, i.e. dead links. They now point at real
//     routes via next/link.
//  4. `min-h-screen` became `min-h-[70vh]`. The site has a fixed navbar and a
//     footer outside this section, so a full viewport height guarantees a
//     needless scrollbar — the existing 404 uses 70vh for the same reason.
//  5. `mr-2` dropped, because the project's Button already applies `gap-2`.

export function NotFound() {
  return (
    <div className="relative flex min-h-[70vh] w-full items-center justify-center overflow-hidden">
      <Empty>
        <EmptyHeader>
          <EmptyTitle className="font-geist text-9xl font-extrabold leading-none tracking-tighter [mask-image:linear-gradient(to_bottom,black_20%,transparent_80%)] [-webkit-mask-image:linear-gradient(to_bottom,black_20%,transparent_80%)]">
            404
          </EmptyTitle>
          <EmptyDescription className="-mt-8 text-nowrap text-[var(--muted)]">
            The page you&apos;re looking for might have been <br />
            moved or doesn&apos;t exist.
          </EmptyDescription>
        </EmptyHeader>
        <EmptyContent>
          <div className="flex gap-2">
            <Button asChild>
              <Link href="/">
                <HomeIcon className="size-4" data-icon="inline-start" />
                Go Home
              </Link>
            </Button>

            <Button asChild variant="outline">
              <Link href="/features">
                <CompassIcon className="size-4" data-icon="inline-start" />{" "}
                Explore
              </Link>
            </Button>
          </div>
        </EmptyContent>
      </Empty>
    </div>
  );
}
