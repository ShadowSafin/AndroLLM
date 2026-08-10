import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const badgeVariants = cva(
  "inline-flex items-center gap-1.5 rounded-pill border px-3 py-1 text-xs font-semibold transition-colors",
  {
    variants: {
      variant: {
        default: "border-transparent bg-[color-mix(in_srgb,var(--accent)_12%,transparent)] text-[var(--accent-deep)] dark:text-[var(--accent-soft)]",
        secondary: "border-[var(--line)] bg-[var(--surface)] text-[var(--muted)]",
        outline: "border-[var(--line)] bg-transparent text-[var(--ink-dim)]",
        ember: "border-transparent bg-[var(--accent)] text-[#fbfaf4]",
        glow: "border-[color-mix(in_srgb,var(--accent)_45%,transparent)] bg-[color-mix(in_srgb,var(--accent)_10%,transparent)] text-[var(--accent-deep)] dark:text-[var(--accent-soft)] shadow-[0_0_24px_-6px_rgba(217,119,87,0.5)]",
      },
    },
    defaultVariants: { variant: "default" },
  }
);

export interface BadgeProps extends React.HTMLAttributes<HTMLDivElement>, VariantProps<typeof badgeVariants> {}

function Badge({ className, variant, ...props }: BadgeProps) {
  return <div className={cn(badgeVariants({ variant }), className)} {...props} />;
}

export { Badge, badgeVariants };