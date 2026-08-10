import { cn } from "@/lib/utils";
import { Reveal } from "@/animations/reveal";

export function SectionHeading({
  eyebrow,
  title,
  description,
  className,
  align = "center",
}: {
  eyebrow: string;
  title: React.ReactNode;
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
      <p className="ledger inline-flex items-center gap-2 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
        <span className="inline-block size-1.5 rounded-full bg-[var(--accent)]" aria-hidden />
        {eyebrow}
      </p>
      <h2 className="text-balance mt-4 font-serif text-3xl font-semibold leading-tight tracking-tight text-[var(--ink)] sm:text-4xl">
        {title}
      </h2>
      {description && (
        <p className="mt-4 text-base leading-relaxed text-[var(--muted)] sm:text-lg">{description}</p>
      )}
    </Reveal>
  );
}