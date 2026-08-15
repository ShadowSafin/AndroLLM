import { Check, Minus } from "lucide-react";
import { SectionHeading } from "@/components/marketing/section-heading";
import { Reveal } from "@/animations/reveal";

const rows = [
  { label: "Local LLMs", typical: "None or limited experiments", andro: "Full LiteRT-LM + .litertlm support" },
  { label: "GPU acceleration", typical: "Rarely available", andro: "OpenCL GPU delegate with CPU fallback" },
  { label: "Multi-turn chat", typical: "None or re-prefill every turn", andro: "KV-cache persistence, diff-based continuation" },
  { label: "Cloud providers", typical: "One proprietary backend", andro: "Any LiteLLM-compatible endpoint" },
  { label: "Persistent memory", typical: "None", andro: "Vector embeddings + hybrid retrieval" },
  { label: "Voice assistant", typical: "Cloud-dependent", andro: "Fully offline: wake word → ASR → LLM → TTS" },
  { label: "Your data", typical: "Sent to provider servers", andro: "Stays on-device by default" },
];

export function Comparison() {
  return (
    <section className="py-24 sm:py-32" aria-label="What makes AndroLLM different">
      <div className="container">
        <SectionHeading
          eyebrow="The difference"
          title={<>Most mobile AI apps ship demos.<br />AndroLLM ships a product.</>}
          description="“Typical mobile AI apps” either route everything through the cloud or ship as a lightweight demo. AndroLLM is a complete, production-quality platform."
        />
        <Reveal className="mx-auto mt-14 max-w-4xl overflow-hidden rounded-card border border-[var(--line)] shadow-card">
          <table className="w-full border-collapse bg-[var(--surface)] text-sm">
            <thead>
              <tr className="border-b border-[var(--line)]">
                <th scope="col" className="px-5 py-4 text-left font-serif text-base font-semibold text-[var(--ink)]">
                  &nbsp;
                </th>
                <th scope="col" className="px-5 py-4 text-left text-xs font-semibold uppercase tracking-wider text-[var(--faint)]">
                  Typical mobile AI apps
                </th>
                <th scope="col" className="px-5 py-4 text-left text-xs font-semibold uppercase tracking-wider text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
                  AndroLLM
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.label} className="border-b border-[var(--line-soft)] last:border-0">
                  <th scope="row" className="whitespace-nowrap px-5 py-3.5 text-left font-semibold text-[var(--ink)]">
                    {row.label}
                  </th>
                  <td className="px-5 py-3.5 text-[var(--faint)]">
                    <span className="inline-flex items-start gap-2">
                      <Minus className="mt-0.5 size-3.5 shrink-0 text-[var(--faint)]" aria-hidden />
                      {row.typical}
                    </span>
                  </td>
                  <td className="px-5 py-3.5 text-[var(--ink-dim)]">
                    <span className="inline-flex items-start gap-2">
                      <Check className="mt-0.5 size-3.5 shrink-0 text-[var(--ok)]" aria-hidden />
                      {row.andro}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Reveal>
      </div>
    </section>
  );
}