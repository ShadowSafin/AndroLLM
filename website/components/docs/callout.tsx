"use client";

import { motion } from "framer-motion";
import { AlertTriangle, Info, Lightbulb, ShieldAlert } from "lucide-react";
import { cn } from "@/lib/utils";
import { useReducedMotion } from "framer-motion";

const variants = {
  note: { icon: Info, label: "Note", classes: "border-[#4FC3F7]/40 bg-[#4FC3F7]/8 text-[var(--ink-dim)]", iconColor: "text-[#4FC3F7]" },
  warning: { icon: AlertTriangle, label: "Warning", classes: "border-[#FFD54F]/40 bg-[#FFD54F]/8 text-[var(--ink-dim)]", iconColor: "text-[#FFD54F]" },
  danger: { icon: ShieldAlert, label: "Heads up", classes: "border-[#E8836C]/50 bg-[#E8836C]/8 text-[var(--ink-dim)]", iconColor: "text-[#E8836C]" },
  tip: { icon: Lightbulb, label: "Tip", classes: "border-[#81C784]/40 bg-[#81C784]/8 text-[var(--ink-dim)]", iconColor: "text-[#81C784]" },
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