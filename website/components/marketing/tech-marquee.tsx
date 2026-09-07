import { Marquee } from "@/components/marketing/marquee";

const items = [
  "LiteRT-LM",
  "OpenCL GPU",
  ".litertlm",
  "sherpa-onnx",
  "whisper.cpp",
  "ONNX Runtime",
  "Kotlin 2.1",
  "Jetpack Compose",
  "LiteLLM",
  "Gemini",
  "Claude",
  "GPT",
  "Grok",
  "Llama",
  "Mistral",
  "MCP",
  "Hilt",
  "Room",
  "Firebase Auth",
];

export function TechMarquee() {
  return (
    <section aria-label="Under the hood" className="border-y border-[var(--line)] bg-[var(--deep)]">
      <div className="container py-8">
        <p className="font-geist mx-auto flex w-fit items-center justify-center gap-2 rounded-3xl border-[2px] border-gray-300/20 bg-gradient-to-tr from-zinc-300/5 via-gray-400/5 to-transparent px-5 py-2 text-sm tracking-tight uppercase text-gray-600 dark:border-white/5 dark:text-gray-400">One unified stack — built on the tools you trust</p>
        <Marquee items={items} />
      </div>
    </section>
  );
}