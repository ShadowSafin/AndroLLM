# Workflow Engine

How AndroLLM turns a single request into a complete autonomous workflow — the engine behind "Research the latest AI breakthroughs, summarize, save as Markdown, then email it" and "Check the weather, and if it rains message Mom and create a calendar reminder."

---

## Autonomous Execution Loop

Instead of `User → LLM → One Tool → Answer`, the engine implements:

```
User
 ↓
Planner (internal graph: Goal → Info → Tools → Order → Dependencies)
 ↓
Tool Selection (Router + Ranker + Health)
 ↓
Execute Tool (validation → permission → confirmation → sandbox + timeout)
 ↓
Observe Result (output validation → confidence → health update → structured log)
 ↓
Need More Work? ── YES ──▶ Replan → Select Next Tool → Execute → Observe
 ↓ NO
Goal Completed / User Interaction / Permission / Unrecoverable / Safety
 ↓
Final Response (grounded in tool results, never blank)
```

The agent **never assumes one tool is enough**. Both chat (`ToolRunCoordinator.runLocalWorkflow`) and voice (`ChatManager.sendMessageStream`) run the same loop.

Implementation: [`ToolRunCoordinator.kt`](../../core/tools/src/main/java/io/androllm/core/tools/coordinator/ToolRunCoordinator.kt) (provider-agnostic), [`AgentPlanner.kt`](../../core/tools/src/main/java/io/androllm/core/tools/agent/AgentPlanner.kt) (internal planner), [`ToolPlanner.kt`](../../core/tools/src/main/java/io/androllm/core/tools/planner/ToolPlanner.kt) (LLM selection).

---

## Goal-Oriented Execution

The loop is driven by the **final goal**, not by the first tool.

> User: *Research quantum computing then SMS Dad.*
> - Goal is **not** `search_web` — it is `Dad receives SMS`.
> - The planner builds `Search → Summarize → Generate SMS → Send → Verify` and the coordinator continues until the SMS is verified sent, refusing early exit even if search succeeded.
> - If the planner emits `[]` prematurely, the coordinator injects `Reminder: still needs [send_sms]` and re-plans.

Goal extraction happens in `AgentPlanner.createPlan(goal = originalRequest)`; completion is `completedSteps >= plan.executionOrder.size` plus output validation.

---

## Dynamic Planning & Execution Graph

Before any tool, `AgentPlanner` builds an `AgentPlan` + `ExecutionGraph`:

```kotlin
data class AgentPlan(
  val goal: String,
  val requiredInformation: List<String>,
  val requiredTools: List<String>,
  val executionOrder: List<PlanStep>,   // PlanStep(id, toolName, dependsOn, condition, parallelGroup)
  val executionGraph: ExecutionGraph    // levels: [[search_web]] → [[export_markdown]] → [[send_email]]
)
```

```
Research
 ↓
Summarize (LLM internal, not a tool)
 ↓
Generate SMS (grounded in summary via variableStore)
 ↓
Send SMS (requiresConfirmation)
 ↓
Verify Success
```

- Splits sequential markers: `then, after, next, finally, before, first, second, last, and then, once finished, after researching, after checking, before sending, followed by`.
- Detects parallel (`Weather || News` when ` and ` without ordering) and conditional (`if it rains`).
- Graph is **internal only** — visible only when `developerMode=true` via `renderDeveloperLog`; normal users see only the final answer and streaming chips.

---

## Continuous Replanning & Observation

After **every** tool the engine asks internally:

> *Has the original request been fully satisfied?*

- **YES** → finish and generate final answer
- **NO** → re-run `ToolPlanner.planLocal(currentHistoryIncludingFeedback)` → next tool(s)

Observation includes `output validation, confidence (0..1), health update, structured log, nextStep prediction`. Failures are fed back as text so the model can clarify or try an alternative without restarting the whole workflow.

---

## Dependency Resolution & Context Propagation

### Automatic Dependency Resolution

```
Search
 ↓  (dependsOn)
Read
 ↓
Summarize
 ↓
Email (dependsOn: Summarize)
```

Never `Email → Search`. `orderByDependencies` sorts calls by `plan.executionOrder`; `ToolSpec.dependencies` declares explicit prerequisites (e.g. `note_save` depends on `search_web`). Unsatisfied dependencies are deferred to the next round.

### Context Propagation

Every tool receives (even if it only declares `arguments`):

- **Original user request**
- **Conversation history** (last 8 turns injected into planner prompt)
- **Previous tool outputs** (`buildLocalToolFeedback` system message + `variableStore`)
- **Relevant memory** (`ContextBuilder` memories + summaries)
- **Current execution state** (`CURRENT CONTEXT` block + workflow variables)

Implementation: `AgentContextBuilder.buildBlock()` re-injected every round; `DeviceContextProvider` supplies time, battery, clipboard, foreground app, device, network.

### Shared Working Memory

`AgentVariableStore` (per-conversation, reset on `beginTurn(scope)`) is the temporary execution memory:

```
Weather → temperature=24°C, rain=80% → SMS Draft (reads weather variable) → Navigation Result
```

`storeToolResultMemory(toolName, summary)` writes `weather, search_results, last_tool_output` automatically; `variable_set/get` implement `WHILE index<n` and `FOR EACH` loops without leaking across chats. Duplicate work is avoided via `ToolResultCache` (pure-read tools, 10-min TTL, 32 entries) — a regenerated answer replays the cached `search_web` instead of re-hitting the network.

---

## Tool Selection & Ranking

1. **Router** (`ToolRouter.route`) — deterministic keyword intent classification (`ATTACHMENT, MATH, DEVICE, WEB, COMMUNICATION, GENERAL`); composite union for `Research then SMS → WEB+COMMUNICATION`; confidence scoring per tool; `hasAttachments` suppresses tools when content already injected.
2. **Ranker** (`ToolRanker.rank`) — when multiple tools solve same problem, ranks by `health 40 + speed 15 + cost 10 + privacy 10 + local 10 + available 5 + queryHits`; `ToolHealthMonitor` supplies `healthScore (successRate*0.5 + latencyScore*0.2 + timeoutPenalty*0.2)`.

The LLM never sees irrelevant tools — the prompt is budgeted to the container's real `contextLength`.

---

## Tool Validation (Registry + Schema + Device + Dependencies)

Before executing, `ToolCallValidator` + `ToolExecutor` validate:

- **Tool exists** (registry `get` → reject unknown, never retry)
- **Parameters valid** (strict JSON Schema: `required, types, enums, extra fields rejected, nullable` via `JsonSchemaValidator`)
- **Permissions granted** (`toolCallingEnabled && isToolEnabled`) + Android runtime (`ToolPermission.runtimePermissions`)
- **Dependencies satisfied** (`spec.dependencies` present in `variableStore`)
- **Device capability** (`spec.isAvailable` — e.g. flashlight, camera)
- **Injection** (`PromptInjectionDetector`)

All checks use `ValidationResult.Invalid(retryable)` — retryable only for formatting/malformed, not for unknown tool or disabled.

---

## Conditional Execution

Supports `IF / ELSE` and nested conditions via `AgentPlanner.Condition` and `ToolRunCoordinator.evaluateConditionalForCall`:

```
Check weather
 ↓
Rain? (weather.contains("rain") || "shower" || "% rain")
 ├─ YES → SMS Mom ("I'll be late") + create calendar reminder (leave early)
 └─ NO  → Return "No SMS sent because rain condition was false" (Success, not failure)
```

Example stored as `Condition(expression="weather.contains(rain)", toolOutputKey="get_weather", expectedContains="rain", onTrue="send_sms", onFalse="skip")`. The coordinator emits `Skipped 'send_sms': condition false` so the final answer can explain.

---

## Parallel Execution

Independent tools run **simultaneously** via `executeCallsParallel` (`async/awaitAll`), merged before next round:

```
Weather || News Search || Calendar → merge outputs → Generate Answer
```

Only pure-read (`cacheable || INFORMATION`, non-confirmation) tools are parallelized; confirmation-gated tools (SMS, calls, email, delete) stay sequential. Do not serialize independent tasks.

---

## Retry, Health Monitoring & Recovery

### Retry Engine

```
Attempt 1 → Retry (500ms) → Retry (1000ms + jitter) → Alternative Tool → Return Error
```

- `TOOL_MAX_ATTEMPTS=3`, `RetryPolicy(initial 500ms, max 8000ms, jitter 150ms)`.
- Never for `user declined`, `disabled in settings`, or confirmation-gated tools.

### Health Monitoring

`ToolHealthMonitor` per tool:

| Metric | Tracked |
|---|---|
| Average latency | EMA `avg = avg*0.7 + latency*0.3` |
| Failure rate | `failures/total` |
| Timeout frequency | `timeouts/total` |
| Success rate | `successes/total` |
| Last success | timestamp + error |

Health influences ranking — unhealthy tools are deprioritized, alternatives preferred.

### Automatic Recovery (without restarting)

| Failure | Recovery |
|---|---|
| Timeouts | retry with backoff |
| Rate limits | backoff delay |
| Network failures | retry → alternative (`search_web` → `github`) |
| Permission changes | clear `"disabled in settings"` error, request via card |
| API failures | retry → alt → feedback to model |
| Malformed responses | `ToolOutputValidator` → retryable failure |
| Missing parameters | `ClarificationEngine` asks specifically, e.g. `"Which Dad contact should I message?"` |

---

## Tool Output Validation

Every output is validated before feeding to later tools (`ToolOutputValidator.validate`):

- Rejects `{}`, blank, missing `temperature/rain` for `get_weather`, missing `path` for `export_pdf`, too-short communication outputs.
- Legitimate empty results (`"No web results for …"`) pass but are flagged; they become honest `"I couldn't find …"` answers.
- Invalid outputs become `Failure(retryable=true)` for retry/alternative.

---

## Loop Detection & Sandboxing

`ToolLoopGuard` per turn:

- **Total cap** 12 (hardened from 5) executions
- **Consecutive cap** 2 same tool back-to-back (different tool resets)
- **Dedupe** `(name,args)` never re-executes
- **Disable on failure** 2 failures or one non-retryable → disabled

When blocked, injects `stopReason` (`"Tool 'calculate' has been used 2 times … Continue reasoning without further tool calls."`) and stops tool rounds — safe abort with explanation.

Sandboxing: every `tool.execute` in `withTimeout(spec.executionTimeoutMs ?: 20s)` + `try/catch` → `ToolResult.Failure`; one failed tool never crashes the agent.

---

## Permission Manager & Confidence

### Permission Handling

| Confirmation mode | What gets confirmed |
|---|---|
| High-risk only (default) | `requiresConfirmation=true` (SMS, calls, email, calendar writes, delete, payments, system) |
| Always | every tool |
| Never | nothing |

Flow: `Permission gate (master + per-tool toggle + ToolPermission.runtimePermissions)` → `Confirmation gate (card + voice)` → `AwaitDecision (5 min auto-deny)` → `Android permission dialog if needed` → execute.

### Tool Confidence

Every execution generates confidence `0..1`:

```
confidence = healthScore*0.5 + outputLengthScore*0.2 + latencyScore*0.3
Weather 99% → Continue
Low (<0.5) → trigger clarification
```

Logged in `StructuredLog.confidence`; low confidence tools are ranked lower in retry.

---

## Working Memory (Variables)

Per-conversation key-value store, reset on `beginTurn(scope)`:

| Tool | Purpose |
|---|---|
| `variable_set` | Write `key=value` (e.g. `index=0`, `weather=rain`) |
| `variable_get` | Read value or full snapshot |

Example — iterating search results:

```
Round 1: search_web("android 17 news") → variable_set(index=0)
Round 2: variable_get(index) → process result[0] → variable_set(index=1)
Round 3: variable_get(index) → process result[1] → … (WHILE index < n)
```

Always available (`permission=null`, never gated).

---

## Agent Context

`AgentContextBuilder` + `DeviceContextProvider` inject `CURRENT CONTEXT` each round:

- Current time / date
- Battery level & charging state
- Clipboard content
- Foreground app
- Device model, OS version, RAM, storage
- Network state
- Workflow variables (`WORKFLOW VARIABLES: weather: rain expected…`)

Lets the model branch on real state without asking the user.

---

## Streaming Execution Updates

Users never wonder if the app froze — per-tool chips stream live:

```
Planning… → Searching Web… → Reading Sources… → Summarizing… → Preparing SMS… → Waiting for confirmation… → Sending… → Done
```

- `ToolRunCoordinator.onActivity` → `ChatViewModel._toolActivity` → `ToolActivityChip`
- `ToolEvent(Started/Succeeded/Failed/Declined)` → `ToolInvocationUi` cards (expandable args/results, capped 8 per turn)
- Throttled to ~60fps, chunked (8k) but complete; voice uses same via `ChatManager.onToolStatus` + TTS.

---

## Execution Logging (Developer Mode)

Developer → Tool Debug (or logcat `ToolRunCoordinator`/`ToolPlanner`):

- Bounded trace `ToolExecutionTraceStore` (200 entries): `prompt → tool → arguments → status → result → error → duration → final LLM output`.
- **Structured logs** `ToolExecutionLogger.StructuredLog`: `executionId (8-char UUID), goal, planner (hidden reasoning), toolSelected, arguments, executionTimeMs, result, validation, nextStep, finalStatus, confidence`.

Hidden from normal users; `renderDeveloperLog` only at `INFO` when `developerMode=true`, otherwise `DEBUG`.

---

## Autonomous Completion

The agent continues until one of:

- **Goal completed** (plan fully executed + outputs validated)
- **User interaction required** (confirmation declined or clarification missing)
- **Permission required** (settings-blocked or runtime denied)
- **Unrecoverable error** (non-retryable *or* alternative also failed, health `disabled`)
- **Safety policy prevents execution** (injection, unknown tool, circular loop)

Never stops because *“one tool finished”* — after each tool it asks *“Does original request require additional actions?”* and injects a reminder if so.

---

## Tool Registry & Extension

Every tool declares via `ToolSpec`:

```kotlin
ToolSpec(
  name = "get_weather",
  description = "Get current weather …",
  parameters = JsonObject{ "location": {type:"string"} },
  permission = ToolPermission.WEATHER,
  requiresConfirmation = false,
  category = ToolCategory.INFORMATION,
  capabilities = ["weather","forecast"],
  estimatedLatencyMs = 2000,
  cost = ToolCost.FREE,
  privacyLevel = PrivacyLevel.NETWORK,
  failureModes = ["timeout","network","not_found"],
  dependencies = [],
  supportedBackends = setOf(LOCAL,CLOUD),
  availableOnDevice = true,
  cacheable = true
)
```

Registry `ToolRegistry` (Hilt `Set<Tool>`) is the sole source of truth; `ToolsModule` binds `@IntoSet`; MCP remote tools appear as `mcp_<server>_<tool>` identically; future tools (camera, file system, shell, OCR) add a `ToolSpec` + implementation with no planner changes.

Best practice: strict schema, `confirmationPrompt`, handle runtime permission inside tool, `cacheable` only for pure reads, `executionTimeoutMs` for long tools, `isAvailable` for hardware-gated tools.

---

## Example Workflows

**1. "Research the latest AI breakthroughs, summarize, save as Markdown, then email it to me."**
```
1. search_web(query="latest AI breakthroughs") → variable_set(search_results=…)
2. export_markdown(content=summary(grounded in search_results)) → path=/exports/…
3. send_email(to="me", subject="AI Breakthroughs", body=summary) → confirm → sent
   Unhealthy? → alternative `github` ranked next.
   Failure? → retry 3× → alt → feedback.
4. Final answer: "Saved Markdown (2 KB) and emailed summary — Done."
```

**2. "Check the weather, and if it rains message Mom that I'll be late and create a calendar reminder to leave early."**
```
1. get_weather(location="current") → "rain expected 80%"
   → condition weather.contains(rain)=true
2. send_sms(phone="Mom", message="I'll be late …") → confirm → sent
   → else: Skipped 'send_sms': condition false → "No SMS — no rain expected"
3. calendar(action=create, title="Leave early", start="tomorrow 07:30") → confirm → created
```

**3. "Download this PDF, summarize, translate to Spanish, convert to DOCX, save locally."**
```
1. list_downloads() → variable_set(pdf_path=…)
2. (LLM summarize uses injected attachment/pdf text)
3. open_translation(text=summary, target="es") → translated
4. export_markdown(content=translated) (DOCX fallback via Markdown export)
5. list_app_files() confirms path
   Parallel: steps 3 and 4 may be sequential (translation → export) due to dependency.
```

**4. "Find nearby Italian restaurants, compare ratings, navigate to the best one, and text my friend the address."**
```
1. search_places(query="Italian restaurants nearby") → [{name, rating}] (health-ranked)
2. (LLM picks best rating)
3. open_navigation(destination="best address") → navigation-opened
4. send_sms(phone="friend", message="Address: …") → confirm → sent
   Parallel: search_places is pure read; navigation + SMS sequential (confirmation).
```

**5. "Search GitHub for Android LLM projects, compare them with AndroLLM, generate a Markdown report, and save it."**
```
1. github(action=repos, query="Android LLM") → health-ranked GitHub results
2. export_markdown(content=MarkdownReport(compare results + AndroLLM context)) → path
   Output validation checks report length; retry if blank.
```

### Single-Tool, Parallel, Conditional, Retry, Error Cases

- **Single-tool**: `"What is 23 × 48?"` → `calculate(expression="23*48")` → `1104` (cacheable, no confirmation).
- **Parallel**: `"Search weather and latest AI news"` → `get_weather || search_web` (async, merged).
- **Conditional**: weather `clear sky` → `Skipped send_sms` → answer `"No rain expected — no SMS sent."`
- **Retry**: `search_web` timeout → `retry 1 (500ms)` → `retry 2 (1000ms)` → `alternative github` → success.
- **Permission**: `send_sms` disabled → `Failure("disabled in settings")` → model explains and asks to enable in `Settings → Automation`.

---

## Confirmation Policy

| Confirmation mode | What gets confirmed |
|---|---|
| High-risk only | `requiresConfirmation` tools (messages, calls, email, calendar writes, delete) |
| Always | every tool action |
| Never | nothing |

Chat card (5 min auto-deny) and voice (spoken + yes/no listening) run concurrently; first decision wins.

---

## Settings → Automation

Exposes: **Tool Calling** master switch (default ON), **Confirmations** mode, **Voice confirmations** toggle, **Max tool rounds** (default 5, max 6), **Device permissions** grant buttons, **Per-tool toggles** grouped by category.

---

## See Also

- [Agent Platform](agent-platform.md) — architecture, health, ranking, safety gates
- [Tool Catalog](tools.md) — every built-in tool, permission, cost, privacy, latency, failure modes
- [MCP Servers](mcp.md) — connecting external tools
- [Accessibility Automation](accessibility-automation.md) — UI-driven steps
- [Voice Assistant](../voice/voice-assistant.md) — same coordinator via `ChatManager`
