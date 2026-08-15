# AI Agent Platform

Deep dive into AndroLLM's on-device AI agent: the capability-based system that
turns natural-language requests into safe, multi-step device actions.

---

## Overview

AndroLLM is not just a chatbot — it is an **agent platform**. When the user
types (or speaks) something like *"check today's weather and text Mom if it
will rain"*, the assistant:

1. **Plans** the request into ordered tool calls,
2. **Executes** them through safety gates (permissions + confirmation),
3. **Feeds results back** and re-plans until the task is complete,
4. **Answers** with a summary grounded in the actual results.

Everything is **capability-based**: no commands are hardcoded. The system
describes ~50 built-in tools to the model, which decides which to call and
with which arguments. New capabilities arrive as new tool implementations (or
as remote MCP tools), not as new special-case code.

---

## Architecture

```
┌────────────────────────────────────────────────────────────────────┐
│                        Chat / Voice Layer                           │
│   ChatViewModel (typed)  ·  ChatManager (voice)                     │
└───────────────────────────────┬────────────────────────────────────┘
                                │
                                ▼
┌────────────────────────────────────────────────────────────────────┐
│                     ToolRunCoordinator                              │
│  Provider-agnostic glue: multi-round workflow, cloud tool calls     │
│  Cloud: OpenAI-style tool_calls · Local: prompt-based planner       │
└───────────────┬──────────────────────────────────┬─────────────────┘
                │                                  │
                ▼                                  ▼
┌────────────────────────────┐     ┌──────────────────────────────────┐
│  ToolPlanner               │     │  ToolExecutor (THE only executor)│
│  Local: JSON-grammar plan  │     │  1. Permission gate              │
│  Cloud: native tools array │     │  2. Confirmation gate            │
└────────────────────────────┘     │  3. Timeout (20 s default)       │
                                   └───────────────┬──────────────────┘
                                                   │
                                                   ▼
                            ┌──────────────────────────────────────┐
                            │  ToolRegistry  (Set<Tool> via Hilt)  │
                            │  built-in tools · accessibility      │
                            │  tools · MCP remote tools            │
                            └──────────────────────────────────────┘
```

Key components, all in `core/tools`:

| Component | Role |
|---|---|
| `Tool` / `ToolSpec` | Contract + static, LLM-readable description (name, description, JSON-schema parameters, permission, confirmation flag) |
| `ToolRegistry` | Registers every available tool (Hilt multibinding `Set<Tool>`) and resolves calls by name |
| `ToolPlanner` | Decides *which* tool(s) to call — never executes anything |
| `ToolExecutor` | The **only** place tool code runs; enforces all safety gates |
| `ToolRunCoordinator` | Provider-agnostic glue: runs the multi-round workflow, executes cloud tool calls, builds feedback messages |
| `ToolConfirmationManager` | Shared confirmation hub (chat card + spoken voice confirmations) |
| `AgentVariableStore` | Per-conversation workflow variables shared between tools |
| `AgentContextBuilder` | Builds the live device-state block injected into every planning round |
| `ToolExecutionTraceStore` | Bounded execution log powering the Developer → Tool Debug screen |

---

## Two Planning Backends

### Cloud (native function calling)

When chat runs through a LiteLLM/OpenAI-compatible provider, the tool list is
passed as the OpenAI `tools` array. The provider emits `tool_calls` natively;
each round streams deltas, executes the accumulated calls, appends
`role="tool"` results to the history, and repeats up to
`maxToolRounds` (default 3).

### Local (JSON planning against LiteRT-LM)

For on-device models, the planner runs a **JSON planning prompt** against the
loaded LiteRT-LM model (the `planLocal` fallback — the tool advertisement is
sized to the model's detected context window, see [Tool Prompt
Budgeting](#tool-prompt-budgeting)):

```json
{ "calls": [ { "name": "tool_name", "arguments": { ... } } ] }
```

- Small token budget (512), temperature 0.1, JSON-Schema-constrained output
  (`PLAN_SCHEMA`) — shaped so small models can satisfy it reliably
- `ToolCallParser` tolerates markdown fences, leading prose and truncated JSON
- The planner prompt lists every available tool with its arguments and
  teaches conditional workflows (IF/ELSE, WHILE, FOR-EACH via variables)
- Every round re-injects the **agent context block** (device facts + variables)

Both paths share the same executor, confirmation flow, and trace store.

---

## Safety Gates

The executor is deliberately the **only** place tool code runs — plugins and
MCP tools can never bypass the gates by calling `Tool.execute` directly.

### 1. Permission gate

- Master switch: **Settings → Automation → Tool Calling** (off by default)
- Per-tool toggles: every registered tool (including future plugins and MCP
  tools) gets its own toggle, grouped by category
- Blocked tools return a clear `Failure` ("disabled in settings")

### 2. Confirmation gate

High-risk tools (`requiresConfirmation = true`: SMS, calls, email) always ask
first. The strictness is configurable:

| Mode | Behavior |
|---|---|
| **High-risk only** (default) | Confirm messages, calls and emails |
| **Always** | Confirm every tool action |
| **Never** | Execute everything immediately |

Confirmations surface in two ways simultaneously:

- **Chat card** — an in-stream "ACTION REQUIRED" card with Approve / Deny
  (live for 5 minutes, auto-denies after)
- **Voice** — the assistant speaks the question aloud and listens for yes/no
  (when voice confirmations are enabled); the first decision wins

Approving an action that needs a missing Android runtime permission (e.g.
`SEND_SMS`) triggers the system permission dialog before the tool runs.

### 3. Timeout

Tool execution is capped at 20 seconds by default (`spec.executionTimeoutMs`
overrides this for multi-step tools like `ui_run`). Runaway plugin tools are
cut off instead of hanging a turn.

### 4. Retry policy

Transient failures are retried **once** with the same arguments — never for
outcomes a retry cannot fix (user-declined, settings-blocked) and never for
confirmation-gated tools (a retry would re-ask a user who already approved).

---

## Chat & Voice Integration

Both interaction surfaces share the full agent stack:

| Surface | Entry point | Notes |
|---|---|---|
| Typed chat | `ChatViewModel.planAndExecuteTools` | Multi-round local workflow, cloud rounds, activity chips, confirmation cards |
| Voice | `ChatManager.sendMessageStream` | Same `ToolRunCoordinator`; confirmations are spoken; TTS speaks normalized results |

Turn-level details:

- **Tool activity chip** — "Running 2 tool calls…" while planning/executing
- **Never-blank guard** — if tools ran but the model produced no text, the
  reply is grounded in the real tool result summary instead of silence
- **Tool Debug screen** (Developer) — per-call log:
  `user prompt → tool → arguments → status → result → error → timing → final LLM output`
- **Trace store** — bounded in-memory log (200 entries) shared by chat & voice

---

## Settings → Automation

The Automation section exposes:

- **Tool Calling** master switch
- **Confirmations** mode selector (High-risk / Always / Never)
- **Voice confirmations** toggle (speak + listen instead of / alongside the card)
- **Device permissions** — one-tap grant buttons for every Android runtime
  permission the tools need, derived automatically from the permission enum
- **Per-tool toggles** grouped by category (Information, Communication,
  Device, Media & Apps, Productivity)

---

## Error Recovery

- A tool failure is fed back to the model as text — the assistant explains
  *why* and asks for guidance; it never silently fails
- Retry-once for transient failures (see gates above)
- Unknown tool names produce a clear "rephrase" message
- The confirmation hub can be cancelled at conversation boundaries so no card
  leaks across chats

---

## See Also

- [Tool Catalog](tools.md) — every built-in tool, its permission and category
- [Workflow Engine](workflow-engine.md) — multi-step execution, variables, conditionals
- [MCP Servers](mcp.md) — connecting external tool servers
- [Accessibility Automation](accessibility-automation.md) — controlling third-party apps
- [Voice Assistant](../voice/voice-assistant.md) — the voice pipeline
