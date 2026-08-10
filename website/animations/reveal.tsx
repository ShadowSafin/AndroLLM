"use client";

import { motion, useReducedMotion, type Variants } from "framer-motion";
import { cn } from "@/lib/utils";
import type { ReactNode } from "react";

const ease = [0.22, 1, 0.36, 1] as const;

export const fadeUp: Variants = {
  hidden: { opacity: 0, y: 24 },
  visible: {
    opacity: 1,
    y: 0,
    transition: { duration: 0.7, ease },
  },
};

export const fadeIn: Variants = {
  hidden: { opacity: 0 },
  visible: { opacity: 1, transition: { duration: 0.8, ease } },
};

export const scaleIn: Variants = {
  hidden: { opacity: 0, scale: 0.95 },
  visible: { opacity: 1, scale: 1, transition: { duration: 0.6, ease } },
};

export function Reveal({
  children,
  className,
  delay = 0,
  variant = fadeUp,
  as = "div",
}: {
  children: ReactNode;
  className?: string;
  delay?: number;
  variant?: Variants;
  as?: "div" | "span" | "li" | "section";
}) {
  const reduce = useReducedMotion();
  const MotionTag = motion[as] as typeof motion.div;

  return (
    <MotionTag
      className={cn(className)}
      variants={variant}
      initial={reduce ? "visible" : "hidden"}
      whileInView={reduce ? undefined : "visible"}
      viewport={{ once: true, margin: "-80px" }}
      transition={reduce ? undefined : { delay }}
      aria-hidden={as === "div" ? undefined : undefined}
    >
      {children}
    </MotionTag>
  );
}

export function Stagger({
  children,
  className,
  delay = 0,
  stagger = 0.09,
}: {
  children: ReactNode;
  className?: string;
  delay?: number;
  stagger?: number;
}) {
  const reduce = useReducedMotion();
  return (
    <motion.div
      className={className}
      initial={reduce ? "visible" : "hidden"}
      whileInView={reduce ? undefined : "visible"}
      viewport={{ once: true, margin: "-80px" }}
      variants={{
        hidden: {},
        visible: { transition: { staggerChildren: stagger, delayChildren: delay } },
      }}
    >
      {children}
    </motion.div>
  );
}

export function StaggerItem({
  children,
  className,
  variant = fadeUp,
}: {
  children: ReactNode;
  className?: string;
  variant?: Variants;
}) {
  return (
    <motion.div className={className} variants={variant}>
      {children}
    </motion.div>
  );
}