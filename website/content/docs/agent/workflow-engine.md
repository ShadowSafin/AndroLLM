# Workflow Engine

How AndroLLM turns a single request into a multi-step, conditionally-branched
execution — the engine behind "check the weather, then text Mom if it will
rain".

---

## The Multi-Round Loop

Both chat and voice run the same local workflow
(`ToolRunCoordinator.runLocalWorkflow`):

```
Round 0:  plan → [get_weather]                       → execute
          feedback: weather = 24°C, clear skies
Round 1:  plan → [send_sms(phone=Mom, message=…)]    → confirm → execute
          feedback: SMS sent
Round 2:  plan → []                                   → done
Answer:   generate final reply grounded in the results
```

- Each round runs the planner against the **current** history (which now
  includes the previous rounds' tool results as a system message)
- The loop stops when the planner emits no calls, or after
  `maxToolRounds` (Settings default **3**, max 6)
- The **agent context block** (device facts + workflow variables) is
  re-injected every round, so the model branches on real state instead of
  asking the user

Cloud mode does the same thing with native `tool_calls` rounds.

---

## Variables (IF / ELSE / WHILE / FOR-EACH)

Workflow variables live in `AgentVariableStore` — a per-conversation key-value
store that is reset at the start of each turn, so tool outputs chain within
the turn and never leak across chats.

| Tool | Purpose |
|---|---|
| `variable_set` | Write `key=value` (e.g. `index=0`, `weather=rain`) |
| `variable_get` | Read a value back in a later round |

Example — iterating over search results:

```
Round 1: search_web("android 17 news") → variable_set(index=0)
Round 2: variable_get(index) → process result[0] → variable_set(index=1)
Round 3: variable_get(index) → process result[1] → … (WHILE index < n)
```

The planner prompt teaches these patterns, and `variable_set`/`variable_get`
are always available (never gated).

---

## Agent Context

`AgentContextBuilder` + `DeviceContextProvider` assemble a **"CURRENT CONTEXT"**
system block injected into every planning round, containing live facts:

- Current time / date
- Battery level & charging state
- Clipboard content
- Foreground app
- Device model, OS version, RAM, storage
- Network state
- Any workflow variables set this turn

This is what lets the model say *"IF battery < 20% THEN enable battery
saver"* — the facts are already in front of it, and tool results update the
variables it can branch on.

---

## Confirmations

### Chat (card)

High-risk actions (SMS, calls, email) publish a `PendingToolConfirmation`
that the chat renders as an in-stream card:

- **Approve** → resumes the suspended executor; if the tool needs a missing
  Android runtime permission, the system dialog is requested first
- **Deny** → the tool returns "user declined" and the LLM explains why the
  action was skipped
- Cards auto-deny after 5 minutes so a turn can never hang

### Voice (spoken)

When voice confirmations are enabled, the assistant **speaks** the question
(e.g. *"Do you want me to send the SMS to Mom? Say yes to confirm…"*) and
listens for a yes/no reply — running concurrently with the card. The first
real decision (spoken or tapped) wins; a broken voice surface abstains and
leaves the card in charge.

### Policy

| Confirmation mode | What gets confirmed |
|---|---|
| High-risk only | `requiresConfirmation` tools (messages, calls, email) |
| Always | every tool action |
| Never | nothing — execute immediately |

---

## Retry & Error Handling

- **Retry-once** for transient failures with identical arguments — never for
  user-declined or settings-blocked actions, never for confirmation-gated
  tools (so an approved action is never re-asked)
- Failures flow back to the model as text; the assistant explains what went
  wrong and asks for guidance
- Each executed call is recorded in the trace store (Developer → Tool Debug):
  prompt → tool → arguments → status → result → error → timing → LLM output

---

## Example Workflows

**"Check today's weather and text Mom if it will rain."**

```
1. get_weather(location=current)              → "rain expected"
2. send_sms(phone=Mom, message="It looks like rain — I'll be late.")
       │ confirmation card → approve → sent (contact name auto-resolved)
3. Final answer summarizes both steps
```

**"Turn on Bluetooth, connect my earbuds, then play my workout playlist."**

```
1. set_bluetooth(enabled=true)
2. launch_app(app=Spotify)            (or ui_run for deeper control)
3. control_music(action=play, track=workout playlist)
```

**"Search GitHub for LiteLLM and summarize the latest release."**

```
1. github(action=repos, query=LiteLLM)
2. github(action=releases, repo=BerriAI/litellm)
3. Final answer summarizes from the release notes
```

---

## See Also

- [Agent Platform](agent-platform.md) — architecture and safety gates
- [Tool Catalog](tools.md) — everything the planner can call
- [MCP Servers](mcp.md) — remote tools in the same workflow
- [Accessibility Automation](accessibility-automation.md) — UI-driven steps
