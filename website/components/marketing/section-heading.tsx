import { ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";
import { Reveal } from "@/animations/reveal";

export function SectionHeading({
  eyebrow,
  title = "",
  description,
  className,
  align = "center",
}: {
  eyebrow: string;
  title?: React.ReactNode;
  description?: string;
  className?: string;
  align?: "center" | "left";
}) {
  return (
    <Reveal
      className={cn(
        "max-w-3xl",
        align === "center" ? "mx-auto text-center" : "text-left",
        className
      )}
    >
      {/* Eyebrow — prompt typography: font-geist tracking-tight uppercase pill */}
      <p
        className={cn(
          "font-geist inline-flex items-center justify-center gap-2 rounded-3xl border-[2px] border-gray-300/20 bg-gradient-to-tr from-zinc-300/5 via-gray-400/5 to-transparent px-5 py-2 text-sm tracking-tight uppercase text-gray-600 dark:border-white/5 dark:text-gray-400",
          align === "center" ? "mx-auto" : ""
        )}
      >
        {eyebrow}
        <ChevronRight className="hidden size-4 opacity-60 sm:inline" aria-hidden />
      </p>
      {/* Title — prompt typography: gradient clipped, tracking-tighter, leading-none, text-balance */}
      <h2
        className={cn(
          "text-balance bg-gradient-to-br from-black from-30% to-black/40 bg-clip-text py-3 font-geist text-3xl font-semibold leading-none tracking-tighter text-transparent sm:text-4xl md:text-5xl dark:from-white dark:to-white/40",
          align === "center" ? "mx-auto" : ""
        )}
      >
        {title}
      </h2>
      {description && (
        <p className="mt-4 text-balance text-lg tracking-tight text-gray-600 dark:text-gray-400 md:text-xl">{description}</p>
      )}
    </Reveal>
  );
}