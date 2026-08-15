"use client";

import { useEffect, useState } from "react";
import { motion, AnimatePresence, useReducedMotion } from "framer-motion";
import { Mic, Send, Wrench, Sparkles, Volume2, Check } from "lucide-react";
import { cn } from "@/lib/utils";

type Mode = "chat" | "voice" | "tools";

const chatScript: Array<{ from: "user" | "ai"; text: string }> = [
  { from: "user", text: "Remind me to call Mom at 6pm" },
  { from: "ai", text: "Setting a reminder for 6:00 PM — and I'll take a note for you too. Anything else while I'm at it?" },
  { from: "user", text: "What's the weather like tomorrow morning?" },
  { from: "ai", text: "Clear skies, 14°C at 8 AM. Should be a good run — I saved that as a preference for tomorrow." },
];

const voiceLines = [
  "Captured · 0.9s",
  "Recognizing locally…",
  '"Hey Andro"',
  "AndroLLM Engine · 32.1 tok/s",
];

const toolCalls = [
  { name: "get_weather", status: "done", detail: "location = home" },
  { name: "create_reminder", status: "done", detail: "time = 18:00" },
  { name: "send_sms", status: "confirm", detail: "text Mom: “On my way”" },
];

export function PhoneMockup() {
  const reduce = useReducedMotion();
  const [mode, setMode] = useState<Mode>("chat");
  const [step, setStep] = useState(0);
  const [voiceLine, setVoiceLine] = useState(0);

  useEffect(() => {
    if (mode !== "chat") return;
    setStep(0);
    const timers: ReturnType<typeof setTimeout>[] = [];
    chatScript.forEach((_, i) => {
      timers.push(setTimeout(() => setStep(i + 1), 1400 + i * 2600));
    });
    return () => timers.forEach(clearTimeout);
  }, [mode]);

  useEffect(() => {
    if (mode !== "voice") return;
    setVoiceLine(0);
    const timers: ReturnType<typeof setTimeout>[] = [];
    voiceLines.forEach((_, i) => {
      if (i === 0) timers.push(setTimeout(() => setVoiceLine(1), 600));
      else timers.push(setTimeout(() => setVoiceLine(i + 1), 1000 + i * 1300));
    });
    return () => timers.forEach(clearTimeout);
  }, [mode]);

  const modes: Array<{ id: Mode; label: string; icon: typeof Mic }> = [
    { id: "chat", label: "Chat", icon: Send },
    { id: "voice", label: "Voice", icon: Mic },
    { id: "tools", label: "Agent", icon: Wrench },
  ];

  const reveal = (i: number) => ({
    initial: reduce ? { opacity: 1 } : { opacity: 0, y: 10 },
    animate: { opacity: 1, y: 0 },
    transition: { duration: 0.35, delay: i * 0.12, ease: [0.22, 1, 0.36, 1] as const },
  });

  return (
    <div className="relative mx-auto w-[290px] shrink-0 select-none sm:w-[320px]">
      <div
        className="absolute -inset-10 -z-10 rounded-full opacity-70 blur-3xl"
        aria-hidden
        style={{ background: "radial-gradient(closest-side, color-mix(in srgb, var(--accent) 22%, transparent), transparent 70%)" }}
      />
      {/* Frame */}
      <div className="relative rounded-[2.8rem] border border-[var(--frame-phone-border)] bg-[var(--frame-phone-bg)] p-2.5 shadow-[var(--frame-phone-shadow)]">
        <div className="pointer-events-none absolute left-1/2 top-2.5 z-20 h-6 w-28 -translate-x-1/2 rounded-full bg-black/85" aria-hidden />
        <div className="relative overflow-hidden rounded-[2.2rem] bg-[var(--canvas)]" style={{ height: 560 }}>
          {/* Status bar */}
          <div className="flex items-center justify-between px-6 pb-1 pt-3 text-[10px] font-semibold text-[var(--faint)]">
            <span>9:41</span>
            <span className="inline-flex items-center gap-1">
              <GpuBar />
            </span>
          </div>

          {/* App header */}
          <div className="flex items-center justify-between px-4 pb-2">
            <div className="flex items-center gap-2">
              <div className="flex size-7 items-center justify-center overflow-hidden rounded-lg border border-[var(--line)] bg-[var(--surface)]">
                <img src="/images/logo.png" alt="" className="h-[150%] w-[150%] object-cover" />
              </div>
              <div>
                <p className="text-[11px] font-bold leading-none text-[var(--ink)]">AndroLLM</p>
                <p className="mt-0.5 text-[9px] text-[var(--faint)]">
                  {mode === "chat" && "Local · Qwen3-0.6B mixed int4"}
                  {mode === "voice" && "Listening · “Hey Andro”"}
                  {mode === "tools" && "Agent · 2 tools running"}
                </p>
              </div>
            </div>
            <span className="rounded-pill border border-[color-mix(in_srgb,var(--ok)_30%,transparent)] bg-[color-mix(in_srgb,var(--ok)_8%,transparent)] px-2 py-0.5 text-[8px] font-bold uppercase tracking-wider text-[var(--ok)]">
              On-device
            </span>
          </div>

          {/* Mode tabs */}
          <div className="scrollbar-none flex gap-1.5 overflow-x-auto px-4 pb-3">
            {modes.map((m) => (
              <button
                key={m.id}
                onClick={() => setMode(m.id)}
                className={cn(
                  "inline-flex items-center gap-1.5 rounded-pill px-3 py-1.5 text-[10px] font-semibold transition-all active:scale-95",
                  mode === m.id
                    ? "bg-[var(--btn-primary-bg)] text-[var(--btn-primary-text)] shadow-ember"
                    : "border border-[var(--line)] bg-[var(--surface)] text-[var(--muted)]"
                )}
                aria-pressed={mode === m.id}
              >
                <m.icon className="size-3" />
                {m.label}
              </button>
            ))}
          </div>

          {/* Screen content */}
          <div className="flex h-[calc(100%-150px)] flex-col overflow-y-auto px-4 pb-3 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
            <AnimatePresence mode="wait">
              {mode === "chat" && (
                <motion.div key="chat" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="space-y-2.5">
                  {chatScript.slice(0, step).map((m, i) =>
                    m.from === "user" ? (
                      <motion.div key={i} {...reveal(i)} className="flex justify-end">
                        <div className="max-w-[85%] rounded-slip rounded-br-sm border border-[color-mix(in_srgb,var(--accent)_35%,var(--line))] bg-[var(--accent)] px-3 py-2 text-[11px] leading-relaxed text-[var(--btn-primary-text)]">
                          {m.text}
                        </div>
                      </motion.div>
                    ) : (
                      <motion.div key={i} {...reveal(i)} className="flex justify-start">
                        <div className="max-w-[88%] rounded-slip rounded-bl-sm border border-[var(--line)] bg-[var(--surface)] px-3 py-2 text-[11px] leading-relaxed text-[var(--ink-dim)] shadow-card">
                          {i === step - 1 && step <= chatScript.length ? (
                            <TypingText text={m.text} onDone={() => undefined} fast />
                          ) : (
                            m.text
                          )}
                        </div>
                      </motion.div>
                    )
                  )}
                  {step < chatScript.length && <TypingDots />}
                </motion.div>
              )}

              {mode === "voice" && (
                <motion.div key="voice" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="flex flex-1 flex-col items-center justify-center gap-4">
                  <div className="relative flex size-20 items-center justify-center">
                    <span className="absolute inset-0 animate-pulse-ring rounded-full border border-[var(--accent)]" aria-hidden />
                    <span className="absolute inset-0 animate-pulse-ring rounded-full border border-[var(--accent)] [animation-delay:-1.2s]" aria-hidden />
                    <div className="flex size-16 items-center justify-center rounded-full bg-[var(--accent)] shadow-ember">
                      <Mic className="size-7 text-[var(--btn-primary-text)]" />
                    </div>
                  </div>
                  <div className="flex h-8 items-end gap-[3px]" aria-hidden>
                    {Array.from({ length: 22 }).map((_, i) => (
                      <span
                        key={i}
                        className="w-[3px] animate-wave-bar rounded-full bg-[var(--accent)]"
                        style={{ height: `${8 + ((i * 37) % 20)}px`, animationDelay: `${i * 0.07}s`, animationDuration: `${0.9 + ((i * 13) % 40) / 100}s` }}
                      />
                    ))}
                  </div>
                  <AnimatePresence>
                    {voiceLine > 0 && (
                      <motion.p key={voiceLine} {...reveal(0)} className="text-center font-mono text-[10px] uppercase tracking-widest text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
                        {voiceLines[voiceLine - 1]}
                      </motion.p>
                    )}
                  </AnimatePresence>
                  <div className="w-full space-y-1.5">
                    {voiceLine >= 2 && <VoiceBubble text="What time is it in Tokyo right now?" speaker="you" />}
                    {voiceLine >= 3 && <VoiceBubble text="Synthesizing · Piper VITS-LJSpeech" speaker="sys" />}
                    {voiceLine >= 4 && <VoiceBubble text="It's 5:12 AM in Tokyo. Andro, if you need the weekend weather there, just ask." speaker="ai" />}
                  </div>
                </motion.div>
              )}

              {mode === "tools" && (
                <motion.div key="tools" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="space-y-2.5 pt-2">
                  <div className="rounded-slip border border-[var(--line)] bg-[var(--surface)] px-3 py-2 text-[11px] text-[var(--ink-dim)] shadow-card">
                    <span className="font-semibold text-[var(--ink)]">Plan:</span> check the weather, note it, then text Mom if rain is expected
                  </div>
                  <AgentTraceRow icon={Sparkles} title="ToolPlanner" subtitle="grammar-constrained JSON · temp 0.1" state="done" delay={0.2} />
                  {toolCalls.map((t, i) => (
                    <motion.div
                      key={t.name}
                      initial={reduce ? { opacity: 1 } : { opacity: 0, x: -12 }}
                      animate={{ opacity: 1, x: 0 }}
                      transition={{ delay: 0.9 + i * 0.7, duration: 0.4, ease: [0.22, 1, 0.36, 1] }}
                      className="flex items-center justify-between rounded-slip border border-[var(--line)] bg-[var(--surface)] px-3 py-2 shadow-card"
                    >
                      <div className="flex items-center gap-2">
                        <span className={cn("flex size-6 items-center justify-center rounded-full", t.status === "confirm" ? "bg-[color-mix(in_srgb,var(--warn)_15%,transparent)]" : "bg-[color-mix(in_srgb,var(--ok)_12%,transparent)]")}>
                          {t.status === "confirm" ? <Check className="size-3 text-[var(--warn)]" /> : <Check className="size-3 text-[var(--ok)]" />}
                        </span>
                        <div>
                          <p className="font-mono text-[10px] font-semibold text-[var(--ink)]">{t.name}</p>
                          <p className="text-[9px] text-[var(--faint)]">{t.detail}</p>
                        </div>
                      </div>
                      <span className={cn("rounded-pill px-2 py-0.5 text-[8px] font-bold uppercase tracking-wider", t.status === "confirm" ? "bg-[color-mix(in_srgb,var(--warn)_15%,transparent)] text-[var(--warn)]" : "text-[var(--ok)]")}>
                        {t.status === "confirm" ? "Confirm" : "done"}
                      </span>
                    </motion.div>
                  ))}
                  <motion.div initial={reduce ? { opacity: 1 } : { opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 3.2, duration: 0.4 }} className="rounded-slip border border-[color-mix(in_srgb,var(--ok)_35%,var(--line))] bg-[color-mix(in_srgb,var(--ok)_6%,var(--surface))] px-3 py-2 text-[10.5px] leading-relaxed text-[var(--ink-dim)]">
                    ⚡ <span className="font-semibold text-[var(--ink)]">Grounded answer:</span> Clear sky tomorrow, no rain — so I didn't text Mom. Reminder set for 6 PM. ✓
                  </motion.div>
                </motion.div>
              )}

            </AnimatePresence>
          </div>
        </div>
      </div>
    </div>
  );
}

function AgentTraceRow({
  icon: Icon,
  title,
  subtitle,
  state,
  delay,
}: {
  icon: typeof Sparkles;
  title: string;
  subtitle: string;
  state: "active" | "done";
  delay: number;
}) {
  const reduce = useReducedMotion();
  return (
    <motion.div
      initial={reduce ? { opacity: 1 } : { opacity: 0, x: -12 }}
      animate={{ opacity: 1, x: 0 }}
      transition={{ delay, duration: 0.4, ease: [0.22, 1, 0.36, 1] }}
      className="flex items-center justify-between rounded-slip border border-[var(--line)] bg-[var(--surface)] px-3 py-2 shadow-card"
    >
      <div className="flex items-center gap-2">
        <span className="flex size-6 items-center justify-center rounded-full bg-[color-mix(in_srgb,var(--faint)_10%,transparent)]">
          <Icon className="size-3 text-[var(--muted)]" />
        </span>
        <div>
          <p className="font-mono text-[10px] font-semibold text-[var(--ink)]">{title}</p>
          <p className="text-[9px] text-[var(--faint)]">{subtitle}</p>
        </div>
      </div>
      <span className={cn("rounded-pill px-2 py-0.5 text-[8px] font-bold uppercase tracking-wider", state === "done" ? "text-[var(--ok)]" : "bg-[color-mix(in_srgb,var(--warn)_15%,transparent)] text-[var(--warn)]")}>
        {state}
      </span>
    </motion.div>
  );
}

function GpuBar() {
  return (
    <span className="inline-flex items-center gap-1">
      <span className="inline-block size-1.5 rounded-full bg-[var(--ok)]" />
      <span className="rounded bg-[color-mix(in_srgb,var(--ok)_12%,transparent)] px-1 py-px font-mono text-[8px] uppercase text-[var(--ok)]">GPU</span>
    </span>
  );
}

function VoiceBubble({ text, speaker }: { text: string; speaker: "you" | "ai" | "sys" }) {
  const [done, setDone] = useState(false);
  useEffect(() => {
    const t = setTimeout(() => setDone(true), 600);
    return () => clearTimeout(t);
  }, []);
  return (
    <div className={cn("flex items-center gap-2", speaker === "you" && "justify-end")}>
      {speaker === "ai" && <Volume2 className="size-3 animate-ember-breathe text-[var(--accent)]" />}
      <div className={cn("rounded-slip px-2.5 py-1.5 text-[10px] leading-snug", speaker === "you" ? "bg-[var(--accent)] text-[var(--btn-primary-text)]" : "border border-[var(--line)] bg-[var(--surface)] text-[var(--ink-dim)] shadow-card")}>
        {done ? text : <span className="inline-block w-1 animate-pulse">▍</span>}
      </div>
    </div>
  );
}

function TypingDots() {
  return (
    <div className="flex justify-start">
      <div className="flex items-center gap-1 rounded-slip rounded-bl-sm border border-[var(--line)] bg-[var(--surface)] px-3 py-2.5 shadow-card" aria-label="Assistant is typing">
        {[0, 1, 2].map((i) => (
          <span key={i} className="size-1.5 animate-ember-breathe rounded-full bg-[var(--accent)]" style={{ animationDelay: `${i * 0.18}s` }} />
        ))}
      </div>
    </div>
  );
}

function TypingText({ text, onDone, fast }: { text: string; onDone: () => void; fast?: boolean }) {
  const [shown, setShown] = useState(0);
  useEffect(() => {
    setShown(0);
    const interval = setInterval(() => {
      setShown((s) => {
        if (s >= text.length) {
          clearInterval(interval);
          onDone();
          return s;
        }
        return s + (fast ? 3 : 2);
      });
    }, fast ? 12 : 18);
    return () => clearInterval(interval);
  }, [text, fast, onDone]);
  return (
    <>
      {text.slice(0, shown)}
      {shown < text.length && <span className="ml-px inline-block h-3 w-[2px] animate-pulse align-middle bg-[var(--accent)]" />}
    </>
  );
}

