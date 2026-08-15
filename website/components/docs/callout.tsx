"use client";

import { motion } from "framer-motion";
import { AlertTriangle, Info, Lightbulb, ShieldAlert } from "lucide-react";
import { cn } from "@/lib/utils";
import { useReducedMotion } from "framer-motion";

const variants = {
  note: { icon: Info, label: "Note", classes: "border-[var(--callout-note-border)] bg-[var(--callout-note-bg)] text-[var(--ink-dim)]", iconColor: "text-[var(--callout-note-text)]" },
  warning: { icon: AlertTriangle, label: "Warning", classes: "border-[var(--callout-warning-border)] bg-[var(--callout-warning-bg)] text-[var(--ink-dim)]", iconColor: "text-[var(--callout-warning-text)]" },
  danger: { icon: ShieldAlert, label: "Heads up", classes: "border-[var(--callout-danger-border)] bg-[var(--callout-danger-bg)] text-[var(--ink-dim)]", iconColor: "text-[var(--callout-danger-text)]" },
  tip: { icon: Lightbulb, label: "Tip", classes: "border-[var(--callout-tip-border)] bg-[var(--callout-tip-bg)] text-[var(--ink-dim)]", iconColor: "text-[var(--callout-tip-text)]" },
} as const;

export function Callout({ message, variant = "note" }: { message: string; variant?: keyof typeof variants }) {
  const v = variants[variant];
  const reduce = useReducedMotion();
  return (
    <motion.div
      initial={reduce ? { opacity: 1 } : { opacity: 0, x: -14 }}
      whileInView={reduce ? undefined : { opacity: 1, x: 0 }}
      viewport={{ once: true, margin: "-60px" }}
      transition={{ duration: 0.55, ease: [0.22, 1, 0.36, 1] }}
      className={cn("rounded-card border px-5 py-4 text-sm leading-relaxed", v.classes)}
    >
      <p className="mb-1 flex items-center gap-2 text-xs font-bold uppercase tracking-widest">
        <v.icon className={cn("size-4", v.iconColor)} aria-hidden />
        {v.label}
      </p>
      <div className="[&>p]:mt-0 [&>p:first-child]:inline">{message}</div>
    </motion.div>
  );
}