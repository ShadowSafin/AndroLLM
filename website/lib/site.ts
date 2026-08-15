export const site = {
  name: "AndroLLM",
  shortName: "AndroLLM",
  tagline: "Private AI. Native Android. Your Models. Your Choice.",
  description:
    "A production-grade AI platform for Android that brings .litertlm model inference on Google's LiteRT-LM runtime with CPU and GPU acceleration, cloud provider integration, persistent memory, and hands-free voice interaction into one unified application.",
  url: "https://androllm.app",
  ghOwner: "ShadowSafin",
  ghRepo: "AndroLLM",
  repo: "https://github.com/ShadowSafin/AndroLLM",
  repoApi: "https://api.github.com/repos/ShadowSafin/AndroLLM",
  issues: "https://github.com/ShadowSafin/AndroLLM/issues",
  discussions: "https://github.com/ShadowSafin/AndroLLM/discussions",
  releases: "https://github.com/ShadowSafin/AndroLLM/releases",
  license: "https://github.com/ShadowSafin/AndroLLM/blob/main/LICENSE.md",
  version: "1.0.0",
  versionCode: 1,
  appId: "io.androllm.app",
  minSdk: 28,
  targetSdk: 35,
  abi: "arm64-v8a",
  minRam: "4 GB",
  recRam: "8 GB+",
  androidMin: "Android 9 (API 28)",
  androidRec: "Android 14 (API 34) recommended",
  founded: 2026,
  wakeWord: '"Hey Andro" / "Okay Andro"',
  theme: "The Parchment Ledger",
  accent: "#D97757",
};

export const repoShield = (label: string, message: string, color: string) =>
  `https://img.shields.io/badge/${encodeURIComponent(label)}-${encodeURIComponent(message)}-${color}`;

export const stats = [
  { value: 7, suffix: "", label: "curated LiteRT (.litertlm) models in the catalog", note: "Qwen · Gemma · DeepSeek" },
  { value: 5, suffix: "", label: "model architectures via LiteRT-LM", note: "gemma3 · gemma4 · qwen2 · qwen3 · deepseek" },
  { value: 50, suffix: "+", label: "built-in agent tools", note: "44 core + 9 UI-automation + MCP remote tools" },
  { value: 31, suffix: "", label: "Gradle modules in three clean tiers", note: "app · core · feature · engine" },
  { value: 12, suffix: "", label: "on-device voice commands", note: "no LLM round-trip needed" },
  { value: 0, suffix: "", label: "analytics, telemetry, crash reporters", note: "zero third-party tracking" },
];

export const providers = [
  { name: "Google Gemini", via: "LiteLLM proxy" },
  { name: "Anthropic Claude", via: "LiteLLM proxy" },
  { name: "OpenAI GPT", via: "Native OpenAI API" },
  { name: "xAI Grok", via: "OpenAI-compatible endpoint" },
  { name: "Meta Llama", via: "Self-hosted LiteLLM" },
  { name: "Mistral", via: "OpenAI-compatible API" },
  { name: "Custom LiteLLM", via: "Any OpenAI-compatible router" },
];

export const navigation = [
  { label: "Features", href: "/features" },
  { label: "Models", href: "/models" },
  { label: "Downloads", href: "/downloads" },
  { label: "Roadmap", href: "/roadmap" },
  { label: "Changelog", href: "/changelog" },
  { label: "Docs", href: "/docs" },
  { label: "Community", href: "/community" },
];