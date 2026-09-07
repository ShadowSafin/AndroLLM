import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const badgeVariants = cva(
  "inline-flex items-center gap-1.5 rounded-3xl border-[2px] px-5 py-2 font-geist text-sm font-medium tracking-tight uppercase transition-colors",
  {
    variants: {
      variant: {
        default: "border-gray-300/20 bg-gradient-to-tr from-zinc-300/5 via-gray-400/5 to-transparent text-gray-600 dark:border-white/5 dark:text-gray-400",
        secondary: "border-[var(--line)] bg-[var(--card-bg)] text-gray-600 dark:text-gray-400",
        outline: "border-gray-300/20 bg-gradient-to-tr from-zinc-300/5 via-gray-400/5 to-transparent text-gray-600 dark:border-white/5 dark:text-gray-400",
        ember: "border-transparent bg-[var(--btn-primary-bg)] text-[var(--badge-accent-solid-text)]",
        glow: "border-white/5 bg-gradient-to-tr from-zinc-300/5 via-gray-400/5 to-transparent text-gray-600 dark:text-gray-400 shadow-[var(--badge-glow-shadow)]",
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