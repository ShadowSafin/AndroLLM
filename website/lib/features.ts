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
  Plug,
  Accessibility,
  BookMarked,
  Paperclip,
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
    tagline: "LiteRT-LM, fully on-device",
    description:
      "Google's LiteRT-LM runtime runs .litertlm language models entirely on your phone — on CPU (XNNPACK) or the OpenCL GPU delegate. No internet required after model download.",
    bullets: [
      "Container-metadata validation and memory estimation before every load",
      "Context budgeting from metadata — context limits, chat templates, and special tokens are read, not guessed",
      "Tool-advertisement cap — 4500-char budget for small Qwen families",
      "Native tool-call markers on Qwen and Gemma models",
      "On-demand debug prompt logging and engine diagnostics",
    ],
    stat: { value: "7", label: "curated .litertlm models — Qwen · Gemma · DeepSeek families" },
  },
  {
    id: "gpu",
    icon: Zap,
    name: "GPU Acceleration",
    tagline: "OpenCL delegate, automatic fallback",
    description:
      "LiteRT-LM runs on the OpenCL GPU delegate when available, with automatic fallback to the CPU (XNNPACK) backend when the GPU is unavailable, low on memory, or misbehaving.",
    bullets: [
      "Real-time diagnostics — backend, gpuFree, gpuTotal, recoveryCount",
      "Automatic CPU fallback on delegate failure — never left stranded",
      "GPU failure recovery counters surfaced in Developer diagnostics",
      "NPU acceleration planned for a future release",
      "Legacy BackendType values (QUALCOMM_QNN, LLAMA_CPP_VULKAN, ONNX_RUNTIME, VULKAN) kept for compatibility only",
    ],
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
    id: "attachments",
    icon: Paperclip,
    name: "Chat Attachments",
    tagline: "Files in the conversation, cloud models",
    description:
      "ChatGPT-style, conversation-scoped attachments for cloud models: attach PDFs, Office documents, text files or images and ask about them — parsed on-device, never indexed, no searchable library.",
    bullets: [
      "PDF, DOCX, PPTX, XLSX, TXT, Markdown, CSV, JSON, HTML, images & screenshots",
      "On-device parsing + OCR; native image parts for vision providers",
      "Conversation-scoped cache — removed with the conversation",
      "Cloud-only: the paperclip simply doesn't exist for local models",
    ],
  },
  {
    id: "privacy",
    icon: ShieldCheck,
    name: "Local-First Guarantee",
    tagline: "Zero telemetry. Zero analytics.",
    description:
      "Every capability runs on-device by default — nothing leaves the phone unless you opt in. No analytics SDKs, no crash reporters, no tracking.",
    bullets: [
      "LLM inference: LiteRT-LM, zero cloud dependency",
      "Voice: wake word → ASR → TTS, fully offline",
      "Memory: vector index in local SQLite",
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
    title: "Context handled by the runtime",
    description:
      "LiteRT-LM manages context and the KV cache inside the runtime. The engine renders every turn with the model's chat template from container metadata — context limits, special tokens, and quantization are read from the .litertlm container, never guessed — then streams tokens back through a Kotlin API with no re-prefill cost on continuation.",
    points: [
      { title: "Metadata-driven rendering", text: "Chat templates and special tokens come from the container's LlmMetadata — every model is formatted exactly as the author intended." },
      { title: "Context budgeting", text: "Tool-advertisement budgets (4500-char cap for small Qwen families) keep prompts inside the model's context window." },
      { title: "Streaming output", text: "Tokens stream through the engine API while generation stays responsive on mid-range hardware." },
    ],
    fact: "Inference runs through LiteRT-LM 0.16.0 and LiteRT 2.2.0 — Maven AARs with no NDK, no CMake, and no native code in the app.",
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
      "The planner picks tools and arguments — JSON-compat planning on local LiteRT-LM, native tool calls on cloud — and the executor is the only place tool code runs. Every call passes a permission gate, a confirmation gate, and a 20-second timeout, then results feed back for up to 6 rounds of re-planning before a grounded final answer.",
    points: [
      { title: "Safety gates", text: "Per-tool toggles in five categories; high-risk actions (SMS, calls, email) always ask first." },
      { title: "Never silent, never blank", text: "Every call is trace-logged in Tool Debug; failures flow back to the model as text, never silently." },
      { title: "Unlimited answers", text: "Generation runs until the model finishes — no arbitrary cutoff on long tasks." },
    ],
    fact: "Tools: weather, web search, SMS, calls, email, calendar, alarms, reminders, clipboard, notes, files, calculator, converters, translation, GitHub, media, PDF/Markdown export, QR & more.",
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
      "A curated 7-model .litertlm catalog filtered by your device's RAM, a HuggingFace browser, .litertlm import with container validation, and a parameter sheet for temperature, top-p, seed and personas.",
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
      "Downloaded models verified: .litertlm validation + SHA-256",
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
    id: "memory-retrieval",
    icon: Database,
    name: "Memory Retrieval",
    tagline: "Private memory, hybrid search",
    description:
      "The persistent memory system embeds memories (locally or via cloud), retrieves them with hybrid vector + keyword search, and injects them into the system prompt before every turn.",
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