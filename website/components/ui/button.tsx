"use client";

import * as React from "react";
import { Slot } from "@radix-ui/react-slot";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-pill text-sm font-semibold transition-all duration-300 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--canvas)] disabled:pointer-events-none disabled:opacity-50 active:scale-[0.97] [&_svg]:pointer-events-none [&_svg]:size-4 [&_svg]:shrink-0",
  {
    variants: {
      variant: {
        primary:
          "bg-[var(--accent)] text-[#fbfaf4] shadow-ember hover:bg-[var(--accent-deep)] hover:shadow-[0_14px_48px_-12px_rgba(179,87,62,0.7)]",
        secondary:
          "bg-[var(--surface)] text-[var(--ink)] hairline hover:border-[var(--accent)] hover:text-[var(--accent-deep)] shadow-card",
        ghost: "text-[var(--muted)] hover:bg-color-mix hover:bg-[color-mix(in_srgb,var(--faint)_12%,transparent)] hover:text-[var(--ink)]",
        outline:
          "hairline bg-transparent text-[var(--ink)] hover:border-[var(--accent)] hover:bg-[color-mix(in_srgb,var(--accent)_6%,transparent)]",
        link: "text-[var(--accent-deep)] underline-offset-4 hover:underline",
      },
      size: {
        default: "h-11 px-6",
        sm: "h-9 px-4 text-xs",
        lg: "h-13 px-8 text-[0.95rem]",
        icon: "size-10",
      },
    },
    defaultVariants: { variant: "primary", size: "default" },
  }
);

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean;
}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild = false, ...props }, ref) => {
    const Comp = asChild ? Slot : "button";
    return <Comp className={cn(buttonVariants({ variant, size, className }))} ref={ref} {...props} />;
  }
);
Button.displayName = "Button";

export { Button, buttonVariants };