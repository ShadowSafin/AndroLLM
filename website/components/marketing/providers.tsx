import { SectionHeading } from "@/components/marketing/section-heading";
import { Reveal } from "@/animations/reveal";
import { providers } from "@/lib/site";

const logos = {
  "Google Gemini": "◇",
  "Anthropic Claude": "◈",
  "OpenAI GPT": "◉",
  "xAI Grok": "✳",
  "Meta Llama": "▲",
  "Mistral": "❋",
  "Custom LiteLLM": "☰",
};

export function Providers() {
  return (
    <section className="border-t border-[var(--line)] bg-[var(--deep)] py-24 sm:py-32" aria-label="Provider support">
      <div className="container">
        <SectionHeading
          eyebrow="Local first, hybrid always"
          title="One interface. Every model source you trust."
          description="Local .litertlm inference is the default — but when you want a frontier model, any OpenAI-compatible provider works through the built-in LiteLLM proxy, with keys encrypted in the Android Keystore."
        />

        <div className="mt-14 grid gap-px overflow-hidden rounded-card border border-[var(--line)] bg-[var(--line)] shadow-card sm:grid-cols-2 lg:grid-cols-4">
          {providers.map((p, i) => (
            <Reveal key={p.name} delay={i * 0.04} className="h-full">
              <div className="flex h-full flex-col items-start gap-1 bg-[var(--surface)] p-6">
                <span className="flex size-9 items-center justify-center rounded-circle border border-[var(--line)] bg-[var(--deep)] font-mono text-sm text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
                  {logos[p.name as keyof typeof logos] ?? "•"}
                </span>
                <p className="mt-3 font-serif text-base font-semibold text-[var(--ink)]">{p.name}</p>
                <p className="mt-1 text-xs leading-relaxed text-[var(--faint)]">{p.via}</p>
              </div>
            </Reveal>
          ))}
          <Reveal delay={0.32} className="h-full">
            <div className="flex h-full flex-col items-start gap-1 bg-[var(--surface)] p-6">
              <span className="flex size-9 items-center justify-center rounded-circle border border-dashed border-[var(--accent)] text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
                +
              </span>
              <p className="mt-3 font-serif text-base font-semibold text-[var(--ink)]">Your endpoint</p>
              <p className="mt-1 text-xs leading-relaxed text-[var(--faint)]">Any OpenAI-compatible URL — bring your own.</p>
            </div>
          </Reveal>
        </div>
      </div>
    </section>
  );
}