"use client";

import { motion, useReducedMotion } from "framer-motion";
import { cn } from "@/lib/utils";

const pipelines = {
  "engine-deep": {
    title: "Multi-turn generation",
    caption: "KV cache = conversation",
    nodes: [
      "User message",
      "Jinja template + prefill at chatPosition",
      "Decode · KV cache grows",
      "chatPosition += tokens",
      "Repeat until done — or context shift",
    ],
    flow: ["TYPED", "VOICE", "AUTOMATION"],
  },
  "voice-deep": {
    title: "Voice pipeline",
    caption: "16 kHz · all-on-device",
    nodes: ["Wake word · “Hey Andro”", "Streaming ASR", "12 local commands / LLM route", "Sentence assembler", "Piper TTS · VAD barge-in"],
    flow: ["KWS", "ASR", "LLM", "TTS"],
  },
  "agent-deep": {
    title: "Agent loop",
    caption: "plan → execute → re-plan",
    nodes: ["Planner · grammar-constrained JSON", "Permission gate", "Confirmation gate", "Executor · 20 s timeout", "Results feed back · ≤ 6 rounds", "Grounded answer"],
    flow: ["PLAN", "EXEC", "REPLAN"],
  },
};

type PipelineId = keyof typeof pipelines;

export function Pipeline({ id }: { id: string }) {
  const reduce = useReducedMotion();
  const p = pipelines[id as PipelineId] ?? pipelines["engine-deep"];

  return (
    <figure className="card overflow-hidden" aria-label={p.title}>
      <div className="flex items-center justify-between border-b border-[var(--line)] bg-[var(--deep)] px-5 py-3.5">
        <div>
          <p className="font-mono text-[10px] uppercase tracking-[0.2em] text-[var(--faint)]">{p.caption}</p>
          <figcaption className="mt-0.5 font-serif text-lg font-semibold text-[var(--ink)]">{p.title}</figcaption>
        </div>
        <div className="hidden gap-1.5 sm:flex">
          {p.flow.map((f, i) => (
            <span key={f} className="rounded-pill border border-[var(--line)] bg-[var(--surface)] px-2.5 py-1 font-mono text-[9px] font-semibold uppercase tracking-widest text-[var(--muted)]">
              {i > 0 && <span className="mx-0.5 text-[var(--accent)]">→</span>}
              {f}
            </span>
          ))}
        </div>
      </div>
      <div className="relative p-5">
        <svg className="absolute inset-x-6 top-0 h-full w-[calc(100%-3rem)] opacity-40" aria-hidden>
          <path
            d="M12 8 L12 320"
            stroke="var(--accent)"
            strokeWidth="1.5"
            strokeDasharray="3 6"
            opacity="0.5"
            fill="none"
          />
        </svg>
        <ol className="relative space-y-3">
          {p.nodes.map((node, i) => (
            <motion.li
              key={node}
              initial={reduce ? { opacity: 1 } : { opacity: 0, x: -14 }}
              whileInView={reduce ? undefined : { opacity: 1, x: 0 }}
              viewport={{ once: true, margin: "-40px" }}
              transition={{ delay: i * 0.12, duration: 0.45, ease: [0.22, 1, 0.36, 1] }}
              className="flex items-center gap-3"
            >
              <span
                className={cn(
                  "flex size-7 shrink-0 items-center justify-center rounded-full border font-mono text-[10px] font-semibold",
                  i === 0
                    ? "border-[var(--accent)] bg-[var(--accent)] text-[var(--btn-primary-text)] shadow-ember"
                    : "border-[var(--line)] bg-[var(--surface)] text-[var(--muted)]"
                )}
              >
                {i + 1}
              </span>
              <span className="rounded-slip border border-[var(--line)] bg-[var(--surface)] px-3.5 py-2 text-[13px] font-medium text-[var(--ink-dim)] shadow-card">
                {node}
              </span>
            </motion.li>
          ))}
        </ol>
      </div>
    </figure>
  );
}