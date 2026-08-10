import Image from "next/image";
import Link from "next/link";
import { cn } from "@/lib/utils";

export function Logo({ className, compact = false }: { className?: string; compact?: boolean }) {
  return (
    <Link href="/" className={cn("group inline-flex items-center gap-2.5", className)} aria-label="AndroLLM — home">
      <span className="relative inline-flex size-9 items-center justify-center overflow-hidden rounded-card border border-[var(--line)] bg-[var(--surface)] shadow-card transition-transform duration-300 group-hover:scale-105">
        <Image
          src="/images/logo.png"
          alt=""
          width={36}
          height={36}
          className="h-[150%] w-[150%] object-cover"
          priority
        />
      </span>
      {!compact && (
        <span className="flex items-baseline gap-1 font-serif text-xl font-semibold tracking-tight text-[var(--ink)]">
          Andro
          <span className="text-gradient-ember">LLM</span>
        </span>
      )}
    </Link>
  );
}