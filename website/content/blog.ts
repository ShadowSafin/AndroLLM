export type BlogPost = {
  slug: string;
  title: string;
  date: string;
  readMin: number;
  excerpt: string;
  body: Array<{ h?: string; p: string[] }>;
};

export const blogPosts: BlogPost[] = [
  {
    slug: "androllm-1-0-private-ai-in-the-open",
    title: "AndroLLM 1.0: private AI, right on the phone, fully in the open",
    date: "August 2026",
    readMin: 4,
    excerpt:
      "Version 1.0 ships local GGUF inference, an on-device memory bank, offline voice, and cloud opt-ins — with every line published under Apache 2.0.",
    body: [
      {
        p: [
          "AndroLLM 1.0 is a production-grade AI platform for Android. It runs real models on your phone: GGUF weights through llama.cpp, accelerated by OpenCL and Vulkan GPU layers. Everything else — memory, voice, identity — is built the same way: local first, cloud only when you say so.",
          "The release signs with the project keystore, stamps a single version across app, docs, and website, and ships per-ABI artifacts through the download page.",
          "It is open source under Apache 2.0: the Kotlin app, the C++ engine, the memory module, the ONNX voice stack, and the documentation all live in one public monorepo where every claim on this website can be checked against the source.",
        ],
      },
      {
        h: "What \"private\" means here",
        p: [
          "No analytics, no telemetry, no crash reporters that phone home. Conversations stay in a local Room database; model weights live in private storage; voice audio never leaves the device. Cloud AI is a manual opt-in per provider, with API keys encrypted in the Android Keystore.",
        ],
      },
      {
        h: "For model runners",
        p: [
          "The catalog lists 101 curated GGUF models across 137 supported architectures. Recommended picks are marked, and the model library page classifies each entry by size band and what it is good for — from small daily-drivers on 4 GB phones to 14B-class models that shine with GPU layers enabled.",
        ],
      },
    ],
  },
  {
    slug: "the-on-device-memory-bank",
    title: "The memory bank: how AndroLLM remembers without a cloud",
    date: "August 2026",
    readMin: 5,
    excerpt:
      "Documents, embeddings, summaries, and two retrieval paths — the memory module is the difference between an API client and an assistant.",
    body: [
      {
        p: [
          "Context windows are precious on-device. AndroLLM’s memory module keeps long-term knowledge in a local database: documents indexed as embeddings, summaries generated for new material, and everything stored in SQLite with an in-memory index for fast k-nearest neighbors.",
          "Retrieval works along two paths. Search is semantic — you ask a question, the assistant fetches the most relevant passages by embedding distance. Match is prefix-based — exact-token recall for lookups where precision beats similarity.",
          "When a memory matches, the system assembles RAG-style context into the prompt, so the model reasons over material that was never in the original conversation. Nothing leaves the device at any point in the pipeline.",
        ],
      },
      {
        h: "Why storage layout matters",
        p: [
          "The memory module lives behind repositories with explicitly documented operations — add, remove, query, and summarize. Models and binaries are separated from state and from the app tier, so each architectural layer stays independently auditable and testable.",
          "Clearing is one tap away in Settings → On-device Memory. The assistant remembers on your terms, not indefinitely by default.",
        ],
      },
    ],
  },
  {
    slug: "a-voice-assistant-with-no-servers",
    title: "A voice assistant with no servers in the path",
    date: "August 2026",
    readMin: 4,
    excerpt:
      "Wake word, speech recognition, and text-to-speech — everything runs from bundled ONNX models in a foreground service.",
    body: [
      {
        p: [
          "AndroLLM’s voice assistant works entirely offline. Wake-word detection listens continuously for \"Hey Andro\" or \"Okay Andro\", hands the utterance to on-device speech recognition, and answers through neural text-to-speech — all from ONNX models bundled with the app.",
          "The assistant runs as a foreground service with an ongoing notification, so it behaves reliably on modern Android while staying transparent to the user.",
        ],
      },
      {
        h: "Battery and intent efficiency",
        p: [
          "Twelve built-in voice commands execute without an LLM round-trip at all — keep the screen on, take a note, pause listening — cutting power draw for the repetitive tasks users do most. A battery-saver mode further reduces the listening duty cycle.",
          "Because audio never leaves the device, there is no privacy trade-off and no voice-button latency. The microphone permission is requested lazily and revocable at any time.",
        ],
      },
    ],
  },
  {
    slug: "website-derived-from-repository",
    title: "Why the website is generated from the repository",
    date: "August 2026",
    readMin: 3,
    excerpt:
      "The site’s documentation pages are built from the repo’s own markdown. One source of truth, zero drift between docs and reality.",
    body: [
      {
        p: [
          "Most product sites maintain a careful copy that quietly diverges from the software. The AndroLLM website instead parses the repository’s documentation directory at build time — the same files developers read — and renders them as pages.",
          "That means the FAQ is the FAQ, the troubleshooting guide is the troubleshooting guide, and the model page is generated from the same product data that seeds the app’s model library. If a claim appears on this site, it exists in the repo.",
        ],
      },
      {
        h: "Static by default",
        p: [
          "The output is a fully static Next.js export: no server, no database, no trackers. GitHub API data (stars, contributors) is fetched in the browser and degrades gracefully when offline — the pages themselves never depend on a live backend.",
        ],
      },
    ],
  },
];