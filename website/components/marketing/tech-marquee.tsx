import { Marquee } from "@/components/marketing/marquee";

const items = [
  "llama.cpp",
  "Vulkan",
  "GGUF",
  "sherpa-onnx",
  "whisper.cpp",
  "stable-diffusion.cpp",
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
        <p className="ledger text-center text-[var(--faint)]">One unified stack — built on the tools you trust</p>
        <Marquee items={items} />
      </div>
    </section>
  );
}