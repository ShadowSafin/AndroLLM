import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const badgeVariants = cva(
  "inline-flex items-center gap-1.5 rounded-pill border px-3 py-1 text-xs font-semibold transition-colors",
  {
    variants: {
      variant: {
        default: "border-transparent bg-[var(--badge-accent-bg)] text-[var(--badge-accent-text)] dark:text-[var(--badge-accent-text-dark)]",
        secondary: "border-[var(--line)] bg-[var(--card-bg)] text-[var(--muted)]",
        outline: "border-[var(--line)] bg-transparent text-[var(--ink-dim)]",
        ember: "border-transparent bg-[var(--btn-primary-bg)] text-[var(--badge-accent-solid-text)]",
        glow: "border-[color-mix(in_srgb,var(--accent)_45%,transparent)] bg-[var(--badge-accent-bg)] text-[var(--badge-accent-text)] dark:text-[var(--badge-accent-text-dark)] shadow-[var(--badge-glow-shadow)]",
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