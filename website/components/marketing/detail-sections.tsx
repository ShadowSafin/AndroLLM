import { Reveal } from "@/animations/reveal";
import { detailFeatures } from "@/lib/features";
import { Pipeline } from "@/components/demos/pipeline-diagrams";
import { cn } from "@/lib/utils";

export function DetailSections() {
  return (
    <section className="border-y border-[var(--line)] bg-[var(--deep)]" aria-label="Deep dives">
      <div className="container space-y-24 py-24 sm:space-y-32 sm:py-32">
        {detailFeatures.map((f, i) => (
          <div key={f.id} className="grid items-center gap-10 lg:grid-cols-2 lg:gap-16">
            <Reveal className={cn(i % 2 === 1 && "lg:order-2")}>
              <p className="font-geist inline-flex items-center justify-center gap-2 rounded-3xl border-[2px] border-gray-300/20 bg-gradient-to-tr from-zinc-300/5 via-gray-400/5 to-transparent px-5 py-2 text-sm tracking-tight uppercase text-gray-600 dark:border-white/5 dark:text-gray-400">
                <f.icon className="size-3.5" aria-hidden />
                {f.eyebrow}
              </p>
              <h2 className="mt-4 text-balance bg-gradient-to-br from-black from-30% to-black/40 bg-clip-text py-1 font-geist text-3xl font-semibold leading-none tracking-tighter text-transparent sm:text-4xl md:text-5xl dark:from-white dark:to-white/40">
                {f.title}
              </h2>
              <p className="mt-4 text-lg tracking-tight leading-relaxed text-gray-600 dark:text-gray-400 md:text-xl">{f.description}</p>
              <div className="mt-6 grid gap-4">
                {f.points.map((p) => (
                  <div key={p.title} className="flex gap-3">
                    <span className="mt-1 flex size-5 shrink-0 items-center justify-center rounded-full bg-[color-mix(in_srgb,var(--accent)_14%,transparent)]">
                      <span className="size-1.5 rounded-full bg-[var(--accent)]" aria-hidden />
                    </span>
                    <div>
                      <h3 className="font-geist text-sm font-semibold tracking-tight leading-tight text-[var(--ink)]">{p.title}</h3>
                      <p className="mt-0.5 text-sm tracking-tight leading-relaxed text-gray-600 dark:text-gray-400">{p.text}</p>
                    </div>
                  </div>
                ))}
              </div>
              <p className="mt-6 rounded-card border border-dashed border-[var(--line)] bg-[var(--surface)] p-4 font-mono text-[11px] tracking-tight leading-relaxed text-gray-600 dark:text-gray-400">
                <span className="mr-2 text-[var(--accent)]">✦</span>
                {f.fact}
              </p>
            </Reveal>
            <Reveal delay={0.12} className={cn(i % 2 === 1 && "lg:order-1")}>
              <Pipeline id={f.id} />
            </Reveal>
          </div>
        ))}
      </div>
    </section>
  );
}