export interface DocEntry {
  slug: string;
  title: string;
  description: string;
}

export interface DocGroup {
  id: string;
  label: string;
  blurb: string;
  docs: DocEntry[];
}

export const docGroups: DocGroup[] = [
  {
    id: "getting-started",
    label: "Getting Started",
    blurb: "Install, first run, and everyday questions.",
    docs: [
      { slug: "getting-started/first-run", title: "Getting Started", description: "First-time installation and setup guide" },
      { slug: "FAQ", title: "FAQ", description: "Frequently asked questions" },
      { slug: "TROUBLESHOOTING", title: "Troubleshooting", description: "Common issues and solutions" },
    ],
  },
  {
    id: "architecture",
    label: "Architecture",
    blurb: "How the system is put together.",
    docs: [
      { slug: "ARCHITECTURE", title: "Architecture", description: "Complete system architecture deep dive" },
      { slug: "PROJECT_STRUCTURE", title: "Project Structure", description: "Module layout and dependency graph" },
      { slug: "DESIGN", title: "Design System", description: "Design system specification — The Parchment Ledger" },
      { slug: "ui/ui-architecture", title: "UI Architecture", description: "Design system, components, theming, responsive layout" },
      { slug: "ui/chat-architecture", title: "Chat Architecture", description: "Chat feature: streaming, markdown, state management" },
      { slug: "backend/database", title: "Database", description: "Room schema, DAOs, migrations, repositories" },
      { slug: "backend/networking", title: "Networking", description: "HTTP clients, download manager, API integration" },
      { slug: "backend/firebase-auth", title: "Firebase Auth", description: "Firebase authentication flow and configuration" },
      { slug: "security/security-architecture", title: "Security", description: "Encryption, threat model, data protection layers" },
    ],
  },
  {
    id: "engine",
    label: "AI Engine",
    blurb: "Inference, formats, and acceleration.",
    docs: [
      { slug: "ai/litert-lm", title: "LiteRT-LM Engine", description: "LiteRT-LM runtime integration, engine lifecycle, compat layer" },
      { slug: "ai/model-formats", title: "Model Formats", description: ".litertlm container format, catalog sources, quantizations" },
      { slug: "ai/acceleration", title: "Acceleration", description: "CPU XNNPACK vs GPU OpenCL delegate, fallback and recovery" },
      { slug: "MODEL_SUPPORT", title: "Model Support", description: "Supported models, formats, quantizations" },
      { slug: "PERFORMANCE", title: "Performance", description: "Performance characteristics and optimization" },
    ],
  },
  {
    id: "features",
    label: "Features",
    blurb: "Voice, memory, and cloud.",
    docs: [
      { slug: "voice/voice-assistant", title: "Voice Assistant", description: "Complete voice pipeline: wake word, ASR, LLM, TTS" },
      { slug: "voice/text-normalization", title: "TTS Text Normalization", description: "TTS text normalization pipeline and OOV spelling" },
      { slug: "memory/memory-architecture", title: "Memory System", description: "Embeddings, retrieval, vector index, write pipeline" },
      { slug: "cloud/cloud-providers", title: "Cloud Providers", description: "LiteLLM integration, provider management, streaming" },
    ],
  },
  {
    id: "agent",
    label: "Agent Platform",
    blurb: "Tools, safety, and automation.",
    docs: [
      { slug: "agent/agent-platform", title: "Agent Platform", description: "AI agent architecture, planning, safety gates, chat/voice integration" },
      { slug: "agent/tools", title: "Tool Catalog", description: "Complete reference of every built-in tool, permission and category" },
      { slug: "agent/workflow-engine", title: "Workflow Engine", description: "Multi-step execution, variables, conditionals, confirmations, retry" },
      { slug: "agent/mcp", title: "MCP Servers", description: "MCP server integration: connect external tools via Streamable HTTP" },
      { slug: "agent/accessibility-automation", title: "Accessibility Automation", description: "Accessibility-driven UI automation, gestures, planners, safety" },
    ],
  },
  {
    id: "development",
    label: "Development",
    blurb: "Build, test, and contribute.",
    docs: [
      { slug: "BUILDING", title: "Building", description: "Build instructions and environment setup" },
      { slug: "DEVELOPMENT", title: "Development Guide", description: "Developer workflow and IDE setup" },
      { slug: "TESTING", title: "Testing", description: "Testing strategy, frameworks, conventions" },
      { slug: "RELEASE_PROCESS", title: "Release Process", description: "Release build and publishing procedures" },
      { slug: "android/permissions", title: "Android Permissions", description: "Complete permissions reference and request flow" },
      { slug: "development/error-handling", title: "Error Handling", description: "Error patterns, Result/UiState, recovery strategies" },
    ],
  },
];

export const allDocs: DocEntry[] = docGroups.flatMap((g) => g.docs);

export function findDoc(slug: string): { entry: DocEntry; group: DocGroup } | undefined {
  for (const group of docGroups) {
    const entry = group.docs.find((d) => d.slug === slug);
    if (entry) return { entry, group };
  }
  return undefined;
}

export function findNeighbors(slug: string): { prev?: DocEntry; next?: DocEntry } {
  const flat = allDocs;
  const i = flat.findIndex((d) => d.slug === slug);
  if (i === -1) return {};
  return {
    prev: i > 0 ? flat[i - 1] : undefined,
    next: i < flat.length - 1 ? flat[i + 1] : undefined,
  };
}

export function titleLine(source: string): string | undefined {
  const match = source.match(/^#\s+(.+)$/m);
  return match ? match[1].trim() : undefined;
}