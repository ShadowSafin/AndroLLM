import type { LucideIcon } from "lucide-react";
import {
  Zap,
  Gauge,
  Cloud,
  BrainCircuit,
  Mic,
  Bot,
  Blocks,
  Palette,
  ImagePlus,
  Lock,
  MessageSquareText,
  Fingerprint,
  Cpu,
  AudioLines,
  Sparkles,
  Rocket,
  Database,
  Search,
  ShieldCheck,
  Wand2,
  Plug,
  Accessibility,
  BookMarked,
} from "lucide-react";

export interface Feature {
  id: string;
  icon: LucideIcon;
  name: string;
  tagline: string;
  description: string;
  bullets: string[];
  stat?: { value: string; label: string };
  accent?: string;
}

export const pillars: Feature[] = [
  {
    id: "local-engine",
    icon: Cpu,
    name: "Local Inference Engine",
    tagline: "GGUF models, fully on-device",
    description:
      "A vendored, stock llama.cpp engine runs GGUF language models entirely on your phone through a 3,700-line JNI bridge. No internet required after model download.",
    bullets: [
      "GGUF validation and memory estimation before every load",
      "KV-cache persistence — multi-turn chat without re-prefill",
      "Streaming token output at up to 60 fps",
      "JSON and constrained (grammar) decoding",
      "Automatic context shift when approaching limits",
    ],
    stat: { value: "137", label: "supported architectures — llama, gemma2, qwen2, deepseek, mistral, phi3 & more" },
  },
  {
    id: "vulkan",
    icon: Zap,
    name: "Vulkan GPU Acceleration",
    tagline: "Hardware-accelerated inference",
    description:
      "A compile-ready Vulkan backend with build-time shader compilation, runtime GPU-vs-CPU correctness validation, and automatic fallback to ARM64 NEON + KleidiAI microkernels.",
    bullets: [
      "Runtime validation: greedy, long-context and sampling tests vs CPU reference",
      "Corruption recovery: NaN/INF logits, invalid tokens, device-lost escalation",
      "Real-time diagnostics — gpuFree, gpuTotal, recoveryCount",
      "CPU fallback never leaves you stranded",
    ],
    stat: { value: "25–60", label: "ms/token on Vulkan (7B Q4) vs 100–300 ms on CPU" },
  },
  {
    id: "voice",
    icon: Mic,
    name: "Offline Voice Assistant",
    tagline: "Say “Hey Andro” and chat",
    description:
      "A complete hands-free pipeline that never touches a server: wake word → speech recognition → LLM → text-to-speech, with barge-in via energy-based VAD.",
    bullets: [
      "Wake word: sherpa-onnx KWS zipformer2 (~3 MB)",
      "Speech recognition: sherpa-onnx streaming ASR (~8 MB)",
      "Text-to-speech: Piper VITS-LJSpeech (~114 MB, lazy-loaded)",
      "12 local voice commands — mute, new chat, settings & more",
      "Foreground service with system overlay and persistent notification",
    ],
    stat: { value: "12", label: "on-device voice commands with no LLM round-trip" },
  },
  {
    id: "agent",
    icon: Bot,
    name: "AI Agent Platform",
    tagline: "Capability-based, safety-gated",
    description:
      "Understand, plan, and execute multi-step tasks through a plan → execute → re-plan workflow with 50+ tools — weather, search, SMS, calls, email, calendar, alarms, notes, GitHub, QR and more.",
    bullets: [
      "Multi-round workflow engine with variables and conditionals",
      "Per-tool permission toggles + high-risk confirmations (chat card & spoken voice)",
      "Contact-name resolution — “text Mom” just works",
      "Effectively unlimited answer length — generation runs until the model finishes",
    ],
    stat: { value: "50+", label: "built-in tools · 44 core + 9 UI-automation + MCP remote tools" },
  },
  {
    id: "mcp",
    icon: Blocks,
    name: "MCP & UI Automation",
    tagline: "Extend beyond the app",
    description:
      "Import tools from any MCP (Streamable HTTP) server — they become first-class mcp_<server>_<tool> capabilities — or drive third-party apps directly through the accessibility engine.",
    bullets: [
      "MCP Streamable HTTP server import with optional bearer auth",
      "Read screens, tap, type, scroll, drag, swipe, pinch",
      "Multi-step app tasks (ui_run) with LLM or heuristic step planning",
      "QR scanning, screenshots, share, and media control tools",
      "Strict confirmations for anything that sends, pays, books or deletes",
    ],
    stat: { value: "9", label: "UI-automation gestures & tools, plus unlimited MCP servers" },
  },
  {
    id: "memory",
    icon: BrainCircuit,
    name: "Persistent Memory",
    tagline: "Remembers across conversations",
    description:
      "Facts, preferences, and projects are extracted after every exchange, embedded locally or via cloud, and injected into future contexts with hybrid retrieval.",
    bullets: [
      "SQLite-backed storage with in-memory vector index",
      "Hybrid search: cosine similarity + keyword matching",
      "Model-independent — memories work with any loaded model",
      "Background indexing via WorkManager",
      "Full privacy: data stays on device unless cloud embedding is enabled",
    ],
    stat: { value: "<10 ms", label: "retrieval for up to 500 memories · ~100 ms at 10,000" },
  },
  {
    id: "imagegen",
    icon: ImagePlus,
    name: "On-Device Image Generation",
    tagline: "Stable Diffusion, zero cloud",
    description:
      "Generate images entirely on-device with stable-diffusion.cpp + Vulkan. A dedicated Images tab: prompt studio, style presets, negative prompts, seeds, batches, steps and CFG control.",
    bullets: [
      "SD 1.5 (~800 MB Q4_K_M) and SDXL GGUF models",
      "Live state machine — Preparing → Loading → Generating → Finalizing, cancel anytime",
      "Save to gallery, share via FileProvider, regenerate, metadata",
      "Strict hardware gating — unsupported devices see the reason, never faked",
      "The agent's generate_image tool runs the same pipeline from chat or voice",
    ],
    stat: { value: "800 MB", label: "SD 1.5 Q4_K_M on-device · SDXL Q4_K_M ≈ 2.4 GB · FLUX.1 Schnell supported" },
  },
  {
    id: "cloud",
    icon: Cloud,
    name: "Cloud, When You Want It",
    tagline: "Any LiteLLM-compatible provider",
    description:
      "Connect to any OpenAI-compatible API through a unified LiteLLM proxy layer — or skip the cloud entirely. Your call, per provider, with keys encrypted in the Android Keystore.",
    bullets: [
      "Google Gemini · Anthropic Claude · OpenAI GPT · xAI Grok · Mistral · self-hosted LiteLLM",
      "API keys encrypted with AES-256/GCM — never in plaintext storage",
      "SSE streaming with exponential backoff retry (1s → 2s → 4s)",
      "Automatic health monitoring and provider failover",
      "Model discovery via /v1/models",
    ],
    stat: { value: "0", label: "cloud dependency by default — every capability runs on-device first" },
  },
  {
    id: "privacy",
    icon: ShieldCheck,
    name: "Local-First Guarantee",
    tagline: "Zero telemetry. Zero analytics.",
    description:
      "Every capability runs on-device by default — nothing leaves the phone unless you opt in. No analytics SDKs, no crash reporters, no tracking.",
    bullets: [
      "LLM inference: vendored llama.cpp, zero cloud dependency",
      "Voice: wake word → ASR → TTS, fully offline",
      "Memory: vector index in local SQLite",
      "Image generation: prompt and image never leave the device",
      "MCP / cloud: strictly opt-in per provider",
    ],
    stat: { value: "0", label: "analytics, telemetry, crash reporters — zero third-party tracking" },
  },
];

export interface DetailFeature {
  id: string;
  icon: LucideIcon;
  eyebrow: string;
  title: string;
  description: string;
  points: { title: string; text: string }[];
  fact: string;
}

export const detailFeatures: DetailFeature[] = [
  {
    id: "engine-deep",
    icon: Cpu,
    eyebrow: "Multi-turn, without the cost",
    title: "The KV cache is the conversation",
    description:
      "The engine keeps a single llama_context across turns. New messages are rendered with a Jinja template and prefilled at the current chat position — no full re-prefill, no wasted tokens. On edit, delete, or regenerate, the sequence is re-rendered from scratch; near the context limit, an in-place shift drops the oldest turns while preserving the system prompt.",
    points: [
      { title: "Diff-based continuation", text: "Only the new message is prefilled — the rest of the cache stays untouched." },
      { title: "Context shift", text: "At pos_check >= nCtx − 4, oldest tokens after the system prompt are discarded in-place." },
      { title: "Streaming at 60 fps", text: "Token delivery throttled to 16 ms intervals so Compose keeps up without O(n²) copying." },
    ],
    fact: "22 JNI functions bridge Kotlin to the native engine — lifecycle, generation, chat templates, diagnostics, and embeddings.",
  },
  {
    id: "voice-deep",
    icon: AudioLines,
    eyebrow: "Hands-free, fully local",
    title: "From “Hey Andro” to a spoken answer — on device",
    description:
      "Microphone audio at 16 kHz flows through sherpa-onnx wake-word spotting, streaming ASR, and a command router that decides between 12 local commands and the LLM — then Piper VITS synthesizes the reply sentence by sentence while VAD listens for barge-in.",
    points: [
      { title: "Spoken confirmations", text: "For high-risk actions the assistant asks aloud — “send the SMS to Mom?” — and listens for yes/no." },
      { title: "Smart TTS normalization", text: "Numbers, dates, currencies, units, math, emoji, URLs, phones, and OOV words are all pronounced correctly (“LLM” → “el el em”)." },
      { title: "Foreground service", text: "Runs with a persistent notification and optional floating overlay; battery-saver mode extends listening." },
    ],
    fact: "Voice commands: mute, unmute, stop speaking, new chat, open settings, open models, switch theme, delete conversation, summarize chat, enable/disable offline mode and voice.",
  },
  {
    id: "agent-deep",
    icon: Sparkles,
    eyebrow: "Plan. Execute. Verify.",
    title: "An agent with guardrails, not just toys",
    description:
      "The planner picks tools and arguments — grammar-constrained JSON on local GGUF, native tool calls on cloud — and the executor is the only place tool code runs. Every call passes a permission gate, a confirmation gate, and a 20-second timeout, then results feed back for up to 6 rounds of re-planning before a grounded final answer.",
    points: [
      { title: "Safety gates", text: "Per-tool toggles in five categories; high-risk actions (SMS, calls, email) always ask first." },
      { title: "Never silent, never blank", text: "Every call is trace-logged in Tool Debug; failures flow back to the model as text, never silently." },
      { title: "Unlimited answers", text: "Generation runs until the model finishes — no arbitrary cutoff on long tasks." },
    ],
    fact: "Tools: weather, web search, SMS, calls, email, calendar, alarms, reminders, clipboard, notes, files, calculator, converters, translation, GitHub, media, PDF/Markdown export, QR & more.",
  },
  {
    id: "imagegen-deep",
    icon: Wand2,
    eyebrow: "A prompt studio in your pocket",
    title: "Diffusion without a data center",
    description:
      "stable-diffusion.cpp powers a dedicated Images tab with style presets, negative prompts, seed control, batches, steps, and CFG. Runtime capability probing — never chipset allowlists — decides between NPU, Vulkan GPU, and CPU; unsupported devices see exactly why.",
    points: [
      { title: "Presets", text: "Realistic, Anime, Cinematic, Fantasy, Product Shot, Poster, Illustration, Wallpaper." },
      { title: "Model manager", text: "SD 1.5 and SDXL GGUF — download, verify, delete, storage tracking." },
      { title: "Agent integration", text: "“generate an image of a cyberpunk city at night” works from chat and voice." },
    ],
    fact: "Hardware rule: CPU + GPU (Vulkan) devices only. GPUs probed at runtime: vendor NPU → NNAPI → Vulkan → CPU.",
  },
];

export const uiFeatures: Feature[] = [
  {
    id: "ui-parchment",
    icon: Palette,
    name: "The Parchment Ledger",
    tagline: "A design system, not a skin",
    description:
      "Warm, editorial, calm. Every conversation is a letter kept in ink on parchment; every action is a terracotta stamp. Built with Jetpack Compose and Material 3.",
    bullets: [
      "Serif editorial headlines, warm paper surfaces, terracotta accent (#D97757)",
      "Adaptive navigation: bottom bar on phones, floating glass rail on tablets and foldables",
      "Light and dark themes with breathing ambient backgrounds",
      "Real-time markdown with syntax-highlighted code blocks",
    ],
  },
  {
    id: "ui-chat",
    icon: MessageSquareText,
    name: "A chat engine tuned for streaming",
    tagline: "60 fps rendering, stable callbacks",
    description:
      "Messages stream with markdown parsed in ten ordered passes, stats panels, smart reply chips, and a conversation drawer — all backed by a strict Result/UiState architecture.",
    bullets: [
      "Conversation drawer with pin, archive, delete, and title search",
      "Export conversations as JSON, Text, or Markdown",
      "Search overlay across titles and message content",
      "Generation stats: tokens/sec, time-to-first-token, backend, KV cache",
    ],
  },
  {
    id: "ui-models",
    icon: BookMarked,
    name: "Model manager & prompt library",
    tagline: "Catalog, download, load, tweak",
    description:
      "A 101-model curated catalog filtered by your device's RAM, a HuggingFace browser, manual GGUF import, and a parameter sheet for temperature, top-p, seed, grammars and personas.",
    bullets: [
      "Compatibility analyzer: will it fit in RAM? Will it GPU-accelerate?",
      "Benchmark tool with tokens/sec, time-to-first-token, memory",
      "Prompt library with 22 one-tap templates across 8 categories",
      "RAM-filtered recommendations from the catalog",
    ],
  },
  {
    id: "ui-security",
    icon: Fingerprint,
    name: "Security as architecture",
    tagline: "Keystore, HTTPS-only, minimal permissions",
    description:
      "API keys encrypted with AES-256/GCM in the Android Keystore, Room databases in the app sandbox, cleartext disabled, and permissions requested lazily — none at launch.",
    bullets: [
      "TLS 1.2+ enforced · usesCleartextTraffic=false",
      "Downloaded models verified: GGUF validation + SHA-256",
      "Guest mode: full functionality without sign-in",
      "Four-layer security model documented in full",
    ],
  },
];

export const fallbackSearch: Feature[] = [
  {
    id: "search",
    icon: Search,
    name: "Search",
    tagline: "Across everything",
    description:
      "A model catalog search with filters, sort, and recommendations; conversation search across titles and message content; and HuggingFace browsing.",
    bullets: ["Catalog: search, filter, sort, recommend", "Chat: title + content search overlay", "HuggingFace: search by author or repo"],
  },
  {
    id: "rag",
    icon: Database,
    name: "RAG",
    tagline: "Retrieval-augmented, private",
    description:
      "Memories are embedded (locally or via cloud), retrieved with hybrid vector + keyword search, and injected into the system prompt before every turn.",
    bullets: ["Hybrid boost: vector + keyword +0.06", "Pinned, importance, recency ranking", "Summaries injected for long conversations"],
  },
  {
    id: "providers",
    icon: Cloud,
    name: "Custom providers",
    tagline: "OpenRouter, Gemini, OpenAI, Ollama & more",
    description:
      "Any LiteLLM-compatible endpoint — including OpenRouter-style routers and self-hosted Ollama proxied through LiteLLM — with per-model overrides and custom headers.",
    bullets: ["Per-model base URL, key, header overrides", "Model discovery via /v1/models", "Health monitoring every 5 minutes"],
  },
  {
    id: "themes",
    icon: Palette,
    name: "Themes",
    tagline: "Light, dark, system",
    description:
      "Two carefully tuned themes — daylight parchment and desk-night — with a breathing atmospheric background. Instant switch, including by voice command.",
    bullets: ["Light: parchment canvas #F5F4ED", "Dark: desk-night canvas #141414", "“Hey Andro, switch theme”"],
  },
];

export const performanceFacts = {
  backends: [
    { device: "Flagship (Snapdragon 8 Gen 2/3)", vulkan: "15–40 tok/s", cpu: "5–15 tok/s" },
    { device: "Mid-range (Snapdragon 7 series, Dimensity 8 series)", vulkan: "8–20 tok/s", cpu: "3–8 tok/s" },
    { device: "Entry (Snapdragon 6 series, older chips)", vulkan: "— (CPU only)", cpu: "2–5 tok/s" },
  ],
  loads: [
    { model: "1.5B (Q4)", cpu: "~3–5 s", vulkan: "~4–6 s" },
    { model: "3B (Q4)", cpu: "~5–8 s", vulkan: "~6–10 s" },
    { model: "7B (Q4)", cpu: "~10–20 s", vulkan: "~8–15 s" },
    { model: "7B (Q8)", cpu: "~15–30 s", vulkan: "~12–20 s" },
  ],
};