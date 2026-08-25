# AI Agent Platform

Deep dive into AndroLLM's autonomous on-device AI agent — the production-grade system that turns a single natural-language request into a complete, validated, and recoverable multi-step workflow.

---

## Overview

AndroLLM is not a simple function caller — it is an **autonomous agent platform**. When the user says *"Research the latest AI breakthroughs, summarize them, save as Markdown, then email it to me"* or *"Check the weather and if it rains message Mom that I'll be late and create a calendar reminder"*, the assistant:

1. **Understands the final goal** (e.g. *Dad receives an SMS*, not just *search completed*),
2. **Plans internally** into an ordered execution graph (`Research → Summarize → Generate SMS → Send → Verify`),
3. **Executes** through validation, permission, and confirmation gates with sandboxing,
4. **Observes** each result, updates health, validates outputs, stores working memory, and **replans**,
5. **Continues** until `Goal completed | User interaction | Permission | Unrecoverable | Safety policy`,
6. **Answers** grounded in real tool results with streaming status and structured dev logs.

Everything is **capability-based**: ~50 built-in tools plus accessibility and MCP remote tools are described to the model via `ToolSpec`; the planner chooses the smallest reliable set. New capabilities arrive as new `Tool` implementations, not special-case code.

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
│  Provider-agnostic glue: multi-round workflow, cloud tool_calls    │
│  Local: prompt-based planner  ·  Cloud: native tools array         │
│  Agent Execution Loop: Plan → Select → Execute → Observe → Replan │
└───────────────┬──────────────────────────────────┬─────────────────┘
                │                                  │
                ▼                                  ▼
┌────────────────────────────┐     ┌──────────────────────────────────┐
│  ToolPlanner + AgentPlanner│     │  ToolExecutor (THE only executor)│
│  Local: JSON-grammar plan  │     │  1. Validation gate              │
│  Cloud: native tools array │     │  2. Device capability gate        │
│  Router + Ranker + Health  │     │  3. Permission gate               │
└────────────────────────────┘     │  4. Confirmation gate             │
                                   │  5. Sandbox + Timeout (20s)       │
                                   │  6. Output validation + Health    │
                                   └───────────────┬──────────────────┘
                                                   │
                                                   ▼
                             ┌──────────────────────────────────────┐
                             │  ToolRegistry  (Set<Tool> via Hilt)  │
                             │  built-in · accessibility · MCP      │
                             └──────────────────────────────────────┘
```

Key components, all in `core/tools`:

| Component | Role |
|---|---|
| `Tool` / `ToolSpec` | Contract + declarative metadata: `name, description, parameters (JSON Schema), permission, requiresConfirmation, category, capabilities, estimatedLatencyMs, cost, privacyLevel, failureModes, dependencies, supportedBackends, availableOnDevice` |
| `ToolRegistry` | Single source of truth `ConcurrentHashMap<String, Tool>`; Hilt multibinding `Set<Tool>`, alias normalization, strict name validation |
| `AgentPlanner` | Internal planner (hidden unless dev mode): `Goal → Required Info → Required Tools → Execution Order → Dependencies → ExecutionGraph`; splits sequential markers, detects parallel/conditional |
| `ToolPlanner` | LLM-driven selection (cloud native `tools` array vs local JSON `{"calls":[...]}` with `PLAN_SCHEMA`); merges graph with router |
| `ToolRouter` | Deterministic routing + confidence: composite union for multi-intent (`Research then SMS → WEB+COMMUNICATION`), sequential/parallel/conditional markers |
| `ToolRanker` + `ToolHealthMonitor` | Health-aware ranking by `accuracy (health 40) + speed 15 + reliability 15 + cost 10 + privacy 10 + local 10` |
| `ToolExecutor` | **Only** place tool code runs; all gates + sandboxing + timeout |
| `ToolRunCoordinator` | Provider-agnostic execution engine: multi-round loop, chunking, retry, cache, parallel/conditional, memory, observation |
| `ToolLoopGuard` | Per-turn guard: total cap **12**, consecutive cap **2**, dedupe `(name,args)`, disable on 2 failures |
| `ToolCallValidator` | Strict input validation (`required, types, enums, extra fields, nullable`) |
| `ToolOutputValidator` | Output validation (rejects `{}`, blank, missing `temperature` for weather, missing `path` for files) |
| `ToolHealthMonitor` | Tracks `avgLatency (EMA), failureRate, timeoutRate, successRate, lastSuccessAt` per tool |
| `ClarificationEngine` | Intelligent clarification: `"Which Dad contact should I message?"` not `"Can you clarify?"` |
| `AgentVariableStore` | Per-conversation `PersistedMap<String,String>` scoped to turn, reset on `beginTurn(scope)` |
| `AgentContextBuilder` + `DeviceContextProvider` | Builds `CURRENT CONTEXT` block (time, battery, clipboard, foreground app, device, RAM, storage, network, variables) injected every round |
| `ToolExecutionLogger` + `ToolExecutionTraceStore` | Bounded structured logs: `executionId, goal, planner, toolSelected, arguments, executionTime, result, validation, nextStep, finalStatus, confidence` (dev mode only) |

---

## Agent Lifecycle

```
User Request (typed or voice)
  │
  ▼
Planner ──▶ Execution Graph (e.g. Research → Summarize → Markdown → Email)
  │         internal only; `renderDeveloperLog` in dev mode shows Graph
  ▼
Tool Selection (Router → Ranker → Health) ──▶ smallest reliable set
  │
  ▼
Validation (exists, schema, device capability, dependencies, injection)
  │
  ▼
Context Propagation (original request + history + previous outputs + memory + variables)
  │
  ▼
Working Memory (variableStore: weather=…, search_results=…) ← previous outputs
  │
  ▼
Execution (permission → confirmation → sandbox + timeout → output validation → health update → confidence)
  │
  ▼
Observation: Has original goal been fully satisfied? ── NO ──▶ Replan → next tool(s)
  │                                                    │
  YES                                                  ▼
  ▼                                            Parallel vs Sequential vs Conditional
Final Response (never blank — grounded in tool results)
```

The lifecycle never assumes one tool is enough. After **every** tool the agent asks internally *“Has the original request been fully satisfied?”* — if not, it selects the next tool, respecting dependencies and execution graph, until one of the autonomous completion conditions is met.

---

## Planning System

### Dynamic Planning (`AgentPlanner`)

Before any execution, `AgentPlanner.createPlan(userRequest, enabledTools, conversationContext, previousOutputs)` produces an `AgentPlan`:

```kotlin
data class AgentPlan(
  val goal: String,                      // "Dad receives SMS about quantum"
  val requiredInformation: List<String>,  // ["quantum summary"]
  val requiredTools: List<String>,        // ["search_web", "send_sms"]
  val executionOrder: List<PlanStep>,     // [search_web(dependsOn=[]), send_sms(dependsOn=[search_web])]
  val dependencies: Map<String, List<String>>,
  val executionGraph: ExecutionGraph,     // levels: [[search_web]] → [[send_sms]]
  val hasConditional: Boolean,
  val hasParallel: Boolean
)
```

- Splits sequential markers: `then, after, next, finally, before, first, second, last, and then, once finished, after researching, after checking, before sending, followed by`.
- Detects parallel (`Weather || News` when ` and ` without ordering dependency) and conditional (`if it rains → SMS else skip`).
- Never exposed to user; `AgentPlanner.renderDeveloperLog` is logged at `INFO` only when `developerMode=true`, otherwise `DEBUG`.

### Tool Selection

1. **Router** classifies the latest user message into intents (`ATTACHMENT, MATH, DEVICE, WEB, COMMUNICATION, GENERAL`) and unions composite requests (`Research then SMS → WEB+COMMUNICATION`) — the model never sees irrelevant tools.
2. **Ranker** scores candidates: `health 40 + speed 15 + cost 10 + privacy 10 + local 10 + available 5 + queryHits`. Health comes from `ToolHealthMonitor.healthScore` (`successRate*0.5 + latencyScore*0.2 + timeoutPenalty*0.2`).
3. **Smallest required set**: only high-confidence tools matching the intents are advertised; the local planner prompt is budgeted to the container's real context (`contextLength - reservedOutput - historyFloor`).

---

## Execution Engine

### Multi-Step Execution & Tool Chaining

One user turn is a **multi-round loop** (`ToolRunCoordinator.runLocalWorkflow`):

```
Round 0: plan → [get_weather] → execute → feedback: "rain expected, 80%"
Round 1: plan → [send_sms(phone=Mom, message=…)] → confirm → execute → feedback: "SMS sent"
Round 2: plan → [] → done
Answer: summarize grounded in results
```

- Each round injects `tool execution results` as a system message before the final answer generation; large results are chunked (8k) but never truncated.
- `maxToolRounds` default **5** (max 6) — bounded, but combined with `ToolLoopGuard.maxTotalCalls=12` supports long workflows (e.g. `Research→Summarize→Markdown→Email`).

### Sequential Execution

Understands `then, after, next, finally, before…` and enforces `orderByDependencies`. Example:

```
"Research quantum computing then SMS Dad"
  Research (search_web) MUST finish before SMS — never reversed
```

Dependency graphs are built from `PlanStep.dependsOn`; out-of-order calls emitted by the model are re-sorted.

### Parallel Execution

Independent reads run **simultaneously** via `executeCallsParallel` (`async/awaitAll`):

```
"Search weather and latest AI news"
  get_weather || search_web → merge → generate answer
```

Only pure-read (`cacheable || INFORMATION`) non-confirmation tools are parallelized; confirmation-gated tools (SMS, calls, email) remain sequential.

### Conditional Execution

Supports `IF / ELSE` and nested conditions:

```
Check weather → Rain? → YES: SMS Mom + calendar reminder
                        NO:  "No SMS sent because rain condition was false"
```

`AgentPlanner.Condition` stores `expression, toolOutputKey, expectedContains`; `ToolRunCoordinator.evaluateConditionalForCall` checks `weather.contains("rain")` and emits `Skipped … condition false` without failure, so the model can explain.

---

## Tool Execution Lifecycle

```
1. Tool Validation      — exists, schema, injection, device capability, dependencies
2. Device Capability    — `spec.isAvailable` (e.g. flashlight present)
3. Permission Gate      — master switch + per-tool toggle `Settings → Automation`
4. Confirmation Gate    — `requiresConfirmation` (SMS, calls, email, calendar changes, delete) → card + voice
5. Sandbox + Timeout    — `withTimeout(spec.executionTimeoutMs ?: 20_000)` + try/catch
6. Output Validation    — `ToolOutputValidator` rejects `{}`, blank, missing fields
7. Health Update        — `ToolHealthMonitor.recordSuccess/Failure(latency, timeout?)`
8. Confidence           — `health*0.5 + length*0.2 + latency*0.3` (e.g. Weather 99%)
9. Structured Log       — `executionId, goal, planner, tool, args, time, result, validation, nextStep, finalStatus`
10. Working Memory       — `variableStore.set(toolName, summary)` + `last_tool_output`
11. Observation          — Goal complete? Need another tool? Need clarification? Need retry?
```

One failed tool never crashes the agent — it returns `ToolResult.Failure(retryable)` and the coordinator decides retry/alternative/clarify.

---

## Retry Manager, Health Monitoring & Recovery

- **Retry with backoff**: `TOOL_MAX_ATTEMPTS=3`, `RetryPolicy(initial 500ms, max 8000ms, jitter 150ms)`; never for confirmation-gated or non-retryable (`user declined`, `disabled in settings`).
- **Alternative tool**: after retries, `findAlternativeTool` picks a health-ranked alternative with same `permission/category` (e.g. `search_web` fallback) and executes once.
- **Automatic recovery without restart**:
  - `Timeouts` → retry with backoff
  - `Rate limits` → backoff delay
  - `Network failures` → retry → alternative
  - `Permission changes` → clear `"disabled in settings"` error, request via card
  - `API failures` → retry → alt → failure feedback to model
  - `Malformed responses` → output validation → retryable failure
  - `Missing parameters` → `ClarificationEngine` asks specifically, e.g. `"Which Dad contact should I message?"`, workflow continues from next step
- **Health monitoring**: per-tool `total, successes, failures, timeouts, avgLatency (EMA), lastSuccessAt`; `healthScore` deprioritizes flaky tools automatically.

---

## Permission Manager & Confirmation Workflow

Sensitive tools require confirmation — everything else auto-executes:

| Confirmation Mode | What gets confirmed |
|---|---|
| **High-risk only** (default) | `requiresConfirmation = true` (messages, calls, email, calendar writes, delete) |
| Always | every tool action |
| Never | nothing |

Flow:

1. `ToolExecutor` checks `settings.shouldConfirm(spec.requiresConfirmation)`
2. `ToolConfirmationManager.awaitDecision(buildConfirmation(call, spec))` suspends
3. **Chat card**: in-stream `ACTION REQUIRED` (5 min auto-deny)
4. **Voice**: spoken *“Do you want me to send SMS to Mom? Say yes…”* + yes/no listening; first decision wins
5. On approve, missing Android runtime permission (`SEND_SMS, READ_CONTACTS, etc`) triggers system dialog before execution.

Implementation: [`ToolExecutor.kt`](../../core/tools/src/main/java/io/androllm/core/tools/executor/ToolExecutor.kt), [`ToolConfirmationManager.kt`](../../core/tools/src/main/java/io/androllm/core/tools/confirmation/ToolConfirmationManager.kt)

---

## Loop Detection & Sandboxing

`ToolLoopGuard` per turn:

- **Total cap** 12 executions
- **Consecutive cap** 2 same tool back-to-back (different tool resets)
- **Dedupe** `(name, args)` never re-executes
- **Disable on failure** 2 failures or one non-retryable → disabled for turn

When blocked, pipeline injects `"The requested tool has already been used … Continue reasoning without further tool calls."` and stops tool rounds — model answers from what it has, safe abort with explanation.

Sandboxing: every `tool.execute` runs in `withTimeout` + `catch (Exception)` → `ToolResult.Failure` not crash.

---

## Tool Registry

Every tool declares via `ToolSpec`:

```kotlin
ToolSpec(
  name = "get_weather",
  description = "Get current weather …",
  parameters = JsonObject{ "location": {type:"string"} },
  permission = ToolPermission.WEATHER,
  requiresConfirmation = false,
  category = ToolCategory.INFORMATION,
  capabilities = ["weather", "forecast"],
  estimatedLatencyMs = 2000,
  cost = ToolCost.FREE,
  privacyLevel = PrivacyLevel.NETWORK,
  failureModes = ["timeout","network","not_found"],
  dependencies = [],
  supportedBackends = setOf(LOCAL, CLOUD),
  availableOnDevice = true,
  cacheable = true
)
```

Registry `ToolRegistry` (Hilt `Set<Tool>`) is the sole source of truth; `ToolsModule` binds each tool `@IntoSet`. MCP remote tools appear as `mcp_<server>_<tool>` identically.

---

## Shared Working Memory & Context Propagation

- **Memory**: `AgentVariableStore.beginTurn(conversationId)` clears per-scope map; tools write `variable_set`, `storeToolResultMemory` writes `weather, search_results, last_tool_output` automatically; next tool reads via `variable_get` or context block.
- **Context**: every planning round injects `AgentContextBuilder.buildBlock()` — `time, battery, clipboard, foreground app, device model, OS, RAM, storage, network` plus `workflow variables`. Cloud path prepends same as system message. No tool runs in isolation.

---

## Tool Confidence & Intelligent Clarification

- **Confidence** 0..1 per execution: `health*0.5 + outputLength*0.2 + latencyScore*0.3`; low confidence (<0.5) triggers clarification instead of silent continuation.
- **Clarification**: `ClarificationEngine.forMissingParam(tool, param)` produces targeted asks:

  - Bad: `"Can you clarify?"`
  - Good: `"Which Dad contact should I message?"` / `"What should the SMS say?"` / `"Which location's weather should I check?"`

  Aggregated for multiple missing params: `"Which contact? Also, what should the SMS say?"`

---

## Streaming Execution Updates

Users never wonder if the app froze:

```
Planning… → Searching Web… → Reading Sources… → Summarizing… → Preparing SMS… → Waiting for confirmation… → Sending… → Done
```

- Chat: `ToolRunCoordinator.onActivity` sets `_toolActivity` chip (`"Running 2 tool calls…"`, `"Searching Web…"`) + `ToolEvent(Started/Succeeded/Failed/Declined)` updates per-tool `ToolInvocationUi` cards live.
- Voice: `ChatManager.onToolStatus` speaks status.
- Throttled to ~60fps (`delay(16ms)`), oversized outputs chunked but still streamed.

---

## Developer Debugging Workflow

**Developer → Tool Debug** (or `ToolExecutionTraceStore`):

- Bounded trace (200 entries) per turn: `prompt → tool → arguments → status → result → error → duration → final LLM output`.
- **Structured execution logs** (`ToolExecutionLogger.StructuredLog`): `executionId (8-char UUID), goal, planner (hidden reasoning), toolSelected, arguments, executionTimeMs, result, validation, nextStep, finalStatus, confidence` — logged at `INFO`, readable in logcat, not exposed to normal users.

Enable: `Settings → Developer → Developer Mode ON`. All planner reasoning remains internal unless this is on.

Implementation: [`ToolRunCoordinator.kt`](../../core/tools/src/main/java/io/androllm/core/tools/coordinator/ToolRunCoordinator.kt), [`ToolExecutionTraceStore.kt`](../../core/tools/src/main/java/io/androllm/core/tools/trace/ToolExecutionTraceStore.kt)

---

## Two Planning Backends

### Cloud (native function calling)

Provider receives OpenAI `tools` array (routed, smallest set). Streams `tool_calls` deltas, executes via `executeCloudToolCalls`, appends `role="tool"` (chunked if >8k) to history, repeats up to `maxToolRounds` (default **5**, max 6) + 4 auto-continuations for `finish_reason=length`.

### Local (JSON planning against LiteRT-LM)

`ToolPlanner.planLocal` runs a 512-token, temperature 0.1, `PLAN_SCHEMA` grammar-constrained prompt against the loaded `.litertlm` model. `ToolCallParser` tolerates fences/prose/truncated JSON. Heuristic fallback synthesizes calls from natural language when the model emits prose.

Both share executor, confirmation, trace, health, and variable store. The tool advertisement is budgeted to the container's real `contextLength` to avoid `token ids are too long` on small Qwen3 repacks.

---

## Best Practices for Implementing New Tools

1. Implement `Tool` + `ToolSpec` with complete metadata (permission, category, latency, privacy, failureModes, dependencies).
2. Register via `ToolsModule`: `@Binds @IntoSet abstract fun bindMyTool(tool: MyTool): Tool` — no core changes needed.
3. Define strict `parameters` JSON Schema (types, required, enums, `no extra fields`).
4. Expect sandboxing: never throw unchecked; return `ToolResult.Failure(retryable=?)` with guidance.
5. Provide `confirmationPrompt` for high-risk tools.
6. Handle runtime permission internally: fail with `"Enable … in Android settings"` if missing.
7. Set `cacheable=true` only for pure reads; `executionTimeoutMs` for long tools (`ui_run`).
8. Declare `isAvailable` based on hardware (e.g. flashlight) — filtered from planner when false.
9. Test: validation (valid/invalid args), execution (success/failure), health recording, alternative fallback; add to `ToolHardeningTest`.

See [Development Guide](../DEVELOPMENT.md#adding-a-new-tool) for step-by-step example.

---

## Extension Points

- **Built-in tools** (`core/tools/tool/impl/*`): weather, search, SMS, calls, email, maps, calendar, notes, files, exports, etc.
- **Accessibility tools** (`core/accessibility/tools/*`): `ui_run` multi-step, gestures, tapped via `UiGestureTool`.
- **MCP remote tools**: `McpConnectionManager` imports `mcp_<server>_<tool>` identically.
- Future: camera, file system, shell, OCR already have stubs — add `ToolSpec` + implementation, bind in `ToolsModule`.

---

## Settings → Automation

- **Tool Calling** master switch (default **ON**)
- **Confirmations** mode (High-risk only / Always / Never)
- **Voice confirmations** toggle
- **Max tool rounds** (default 5, range 1–6)
- **Device permissions** grant buttons (auto-derived from `ToolPermission.runtimePermissions()`)
- **Per-tool toggles** grouped by category (Information, Communication, Device, Media & Apps, Productivity)

---

## Autonomous Completion

The agent continues until:

- **Goal completed** (`planExecutionOrder` fully executed + output validated)
- **User interaction required** (confirmation declined or clarification needed)
- **Permission required** (settings-blocked or runtime missing)
- **Unrecoverable error** (non-retryable failure twice, or alternative also failed)
- **Safety policy prevents execution** (injection detected, unknown tool)

Never stops because *“one tool finished”* — it asks internally *“Does original request require additional actions?”* and injects a reminder if so.

---

## See Also

- [Tool Catalog](tools.md) — every built-in tool, permission, category, cost, privacy
- [Workflow Engine](workflow-engine.md) — variables, conditionals, health, recovery, streaming
- [MCP Servers](mcp.md) — connecting external tools
- [Accessibility Automation](accessibility-automation.md) — controlling third-party apps
- [Voice Assistant](../voice/voice-assistant.md) — voice pipeline sharing same coordinator
- [Architecture](../ARCHITECTURE.md#agent-architecture) — layered view of agent components
