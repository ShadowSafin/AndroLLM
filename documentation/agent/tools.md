# Tool Catalog

Complete reference for every built-in tool in the autonomous agent platform. Tools are declared via `ToolSpec` in `core/tools` (plus `core/accessibility` for UI automation) and registered via Hilt multibinding `Set<Tool>` — the planner sees them identically.

> **Notation:** 🔒 = requires user confirmation before executing (SMS, calls, email, calendar writes, delete). Permissions map to toggles in **Settings → Automation** and to Android runtime permissions. Categories match the settings grouping (Information, Communication, Device, Media & Apps, Productivity). 🔒 tools respect the **High-risk only / Always / Never** mode.

---

## ToolSpec Registry Contract

Every tool declares (see [`ToolSpec.kt`](../../core/tools/src/main/java/io/androllm/core/tools/api/ToolSpec.kt)):

```kotlin
ToolSpec(
  name = "get_weather",                          // snake_case, LLM-visible
  description = "Get current weather …",         // 1–2 sentences, LLM prompt
  parameters = JsonObject{ "location": {type:"string"} }, // JSON Schema (required, types, enums, no extra fields)
  permission = ToolPermission.WEATHER,            // toggle in Settings → Automation
  requiresConfirmation = false,                   // true for SMS, calls, email, calendar writes, delete, payments, system
  category = ToolCategory.INFORMATION,
  capabilities = ["weather","forecast"],           // for capability-aware ranking
  estimatedLatencyMs = 2000,                      // for speed ranking
  cost = ToolCost.FREE,                          // FREE < NETWORK < PAID
  privacyLevel = PrivacyLevel.NETWORK,           // LOCAL < NETWORK < CLOUD < SENSITIVE
  failureModes = ["timeout","network","not_found"], // for recovery planning
  dependencies = [],                             // e.g. ["search_web"] for note_save after search
  supportedBackends = setOf(LOCAL, CLOUD),
  availableOnDevice = true,                      // false when hardware missing (e.g. flashlight)
  cacheable = true                               // pure read, replayed on regenerate
)
```

The planner uses this contract for **routing** (smallest reliable set), **ranking** (health + speed + cost + privacy + local), **validation** (strict schema), **permission** gating, and **retry** (alternative tool with same `permission/category`).

| Field | Used For |
|---|---|
| `capabilities` | Ranking and permission families |
| `required permission` | Settings toggle + Android `runtimePermissions()` |
| `input schema` (`parameters`) | Strict validation (`required, types, enums, extra fields rejected`) |
| `output schema` | `ToolOutputValidator` (e.g. weather must contain `temperature`/`rain`, files must contain `path`) |
| `failure modes` | `ToolHealthMonitor` + retry → alternative |
| `estimated latency` | Ranker speed score |
| `cost` | Ranker cost score |
| `privacy level` | Ranker privacy score |
| `dependencies` | `ToolRunCoordinator.orderByDependencies` |

---

## Information

| Tool | Name | Arguments | Permission | Cost | Privacy | Latency | Failure Modes | Notes |
|---|---|---|---|---|---|---|---|---|
| Weather | `get_weather` | `location` | Weather | FREE | NETWORK | 2000ms | timeout, network, not_found | `cacheable`, Open-Meteo, 3-day forecast |
| Web search | `search_web` | `query` | Web Search | FREE | NETWORK | 2000ms | timeout, network, empty | `cacheable`, DDG→Bing fallback, chunked |
| Device info | `get_device_info` | — | Device Info | FREE | LOCAL | 100ms | none | battery, RAM, storage via `DeviceInfoTool` |
| Battery | `get_battery` | — | Device Info | FREE | LOCAL | 100ms | none | `cacheable` |
| Calculator | `calculate` | `expression` | Calculator | FREE | LOCAL | 50ms | malformed | `cacheable`, precedence + parentheses |
| Unit converter | `convert_units` | `value, from, to` | Calculator | FREE | LOCAL | 50ms | unsupported | `cacheable` |
| Currency | `convert_currency` | `amount, from, to` | Calculator | FREE | NETWORK | 500ms | network | `cacheable` |
| GitHub | `github` | `action` (`repos`/`releases`), `query`, `repo?` | GitHub | FREE | NETWORK | 1500ms | timeout, not_found | — |
| Translate | `open_translation` | `text, target?` | Translation | FREE | NETWORK | 500ms | no_app | Opens Google Translate |
| Notifications | `read_notifications` | — | Notifications | FREE | LOCAL | 200ms | permission | — |

---

## Communication

| Tool | Name | Arguments | Permission | Cost | Privacy | Latency | Failure Modes | Notes |
|---|---|---|---|---|---|---|---|---|
| SMS 🔒 | `send_sms` | `phone` (number *or* contact name), `message` | SMS (`SEND_SMS`) | NETWORK | SENSITIVE | 1000ms | not_found, denied, timeout | Resolves contacts, multipart, `confirmationPrompt="send the SMS to {phone}"` |
| Phone call 🔒 | `make_call` | `phone` | Phone Calls (`CALL_PHONE`) | NETWORK | SENSITIVE | 1000ms | denied, no_app | — |
| Email 🔒 | `send_email` | `to, subject, body` | Email | NETWORK | SENSITIVE | 800ms | no_app, denied | Opens email draft, `confirmationPrompt="open the email to {to}"` |
| Contacts | `find_contacts` | `name` | Contacts (`READ_CONTACTS`) | FREE | SENSITIVE | 300ms | denied, not_found | — |
| Share | `share_text` | `text, title?` | Share | FREE | LOCAL | 300ms | no_app | Share sheet |

**Recipient resolution:** `send_sms` accepts contact name ("Mom") or formatted number. Non-numeric entries are resolved via `ContactResolver` (off-main thread, `READ_CONTACTS`); long/unicode messages are split via `SmsManager.divideMessage`; unresolved names fail with specific guidance instead of silent drop.

---

## Device

| Tool | Name | Arguments | Permission | Cost | Privacy | Latency | Failure Modes | Notes |
|---|---|---|---|---|---|---|---|---|
| Running apps | `get_running_apps` | — | Device Info | FREE | LOCAL | 200ms | none | — |
| Volume | `set_volume` | `level` 0–100 | System | FREE | LOCAL | 200ms | none | — |
| Flashlight | `set_flashlight` | `on` | Flashlight | FREE | LOCAL | 200ms | unavailable | `availableOnDevice` check |
| Bluetooth | `set_bluetooth` | `enabled` | Bluetooth | FREE | LOCAL | 500ms | timeout | — |
| Wi-Fi | `set_wifi` | `enabled` | Wi-Fi | FREE | LOCAL | 500ms | timeout | — |
| Screenshot | `take_screenshot` | — | Screenshot | FREE | LOCAL | 800ms | unavailable | — |

---

## Media & Apps

| Tool | Name | Arguments | Permission | Cost | Privacy | Latency | Failure Modes | Notes |
|---|---|---|---|---|---|---|---|---|
| App launcher | `launch_app` | `app` (name or package) | App Launcher | FREE | LOCAL | 500ms | not_found, no_app | Fuzzy `AppSearch` (Instagram→`insta`, YouTube→`yt`) |
| Camera | `open_camera` | — | Camera (`CAMERA`) | FREE | LOCAL | 500ms | no_app, denied | — |
| Gallery | `open_gallery` | — | Media | FREE | LOCAL | 500ms | no_app | Chooser `image/*` |
| Music | `control_music` | `action` (`play`/`pause`/`next`/`prev`), `track?` | Music | FREE | LOCAL | 300ms | no_app | MediaController |
| Voice recorder 🔒 | `record_voice` | `duration?` (1–60s) | Voice Recorder (`RECORD_AUDIO`) | FREE | LOCAL | 1000ms/10s | denied, unavailable | 90s timeout, `ACTION_REQUIRED` |

---

## Productivity

| Tool | Name | Arguments | Permission | Cost | Privacy | Latency | Failure Modes | Notes |
|---|---|---|---|---|---|---|---|---|
| Clipboard | `copy_to_clipboard` | `text` | Clipboard | FREE | LOCAL | 100ms | none | — |
| Reminder | `create_reminder` | `text, time?` | Alarms & Reminders | FREE | LOCAL | 500ms | parse_error | — |
| Alarm | `set_alarm` | `time` natural lang | Alarms & Reminders | FREE | LOCAL | 500ms | parse_error | — |
| Calendar 🔒 | `calendar` | `action` (`create`/`read`), `title, start?, end?, description?, location?` | Calendar (`READ/WRITE_CALENDAR`) | FREE | LOCAL | 800ms | denied, parse_error, no_calendar | `confirmationPrompt` for create |
| Navigation | `open_navigation` | `destination, mode?` (`drive`/`walk`/`transit`/`bicycle`) | Maps | FREE | NETWORK | 800ms | no_app | `geo:` fallback |
| Nearby places | `search_places` | `query` | Maps | FREE | NETWORK | 800ms | no_app | — |
| Notes | `note_save` | `title, content` | Notes | FREE | LOCAL | 200ms | io | `cacheable=false`, app-private `files/notes/` |
| Notes list | `note_list` | — | Notes | FREE | LOCAL | 200ms | none | Sorted by modified |
| Notes get | `note_get` | `title` | Notes | FREE | LOCAL | 200ms | not_found | — |
| Notes delete 🔒 | `note_delete` | `title` | Notes | FREE | LOCAL | 200ms | not_found | `requiresConfirmation` |
| PDF export | `export_pdf` | `title?, content` | Files & Exports | FREE | LOCAL | 1000ms | io | `PdfDocument` paginated, A4, `files/exports/` |
| Markdown export | `export_markdown` | `title?, content` | Files & Exports | FREE | LOCAL | 500ms | io | — |
| Downloads | `list_downloads` | — | Files & Exports | FREE | LOCAL | 500ms | none | MediaStore (Android 10+) or public dir |
| App files | `list_app_files` | — | Files & Exports | FREE | LOCAL | 500ms | none | `exports/`, `notes/`, `recordings/` |
| Variable set | `variable_set` | `key, value` | — (always) | FREE | LOCAL | 10ms | none | Workflow memory, never gated |
| Variable get | `variable_get` | `key?` | — (always) | FREE | LOCAL | 10ms | none | Read or full snapshot |

---

## UI Automation (Accessibility)

See [Accessibility Automation](accessibility-automation.md) for gestures, planners, and safety.

| Tool | Name | Arguments | Permission | Notes |
|---|---|---|---|---|
| Read screen | `ui_read_screen` | — | UI Automation | `UiTreeBuilder` bounded traversal |
| Tap | `ui_click` | `text` / `description` / `id` | UI Automation | `UiGestureTool` |
| Type | `ui_type` | `text, into?` | UI Automation | — |
| Scroll | `ui_scroll` | `direction` | UI Automation | — |
| Swipe | `ui_swipe` | `from, to` | UI Automation | — |
| Gesture | `ui_gesture` | `type` (`tap`/`long-press`/`double-tap`/`drag`/`swipe`/`pinch`), `from, to` | UI Automation | — |
| Navigate | `ui_navigate` | `action` (`back`/`home`/`recents`…) | UI Automation | — |
| Multi-step task | `ui_run` | `goal` plain lang | UI Automation | `executionTimeoutMs=90_000`, LLM planner `HeuristicActionPlanner` fallbacks |
| QR scanner | `scan_qr` | — | QR Scanner | `QrScanTool` |

`ui_run` is the only long-running tool (90s); confirmation doubles as runtime-permission request when needed.

---

## MCP Remote Tools

Every tool imported from a connected MCP server appears as `mcp_<server>_<tool>` and behaves identically (same gates, ranking, trace). See [MCP Servers](mcp.md).

---

## Permissions & Safety Mapping

| Permission | Runtime permission(s) | Typical tools |
|---|---|---|
| SMS | `SEND_SMS` | `send_sms` |
| Contacts | `READ_CONTACTS` | `find_contacts` (+ `send_sms` resolution) |
| Phone Calls | `CALL_PHONE` | `make_call` |
| Calendar | `READ_CALENDAR`, `WRITE_CALENDAR` | `calendar` |
| Voice Recorder | `RECORD_AUDIO` | `record_voice` |
| Location | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | `get_device_info`, `search_places` |
| (others) | — | One-tap grant buttons derived from `ToolPermission.runtimePermissions()` in Settings → Automation |

All tools declare `ToolPermission` → toggle in **Settings → Automation** → Android runtime permission requested lazily via confirmation card or grant button. Tools without `permission=null` (e.g. `calculate, variable_*`) are never gated.

---

## Tool Output Validation & Health

- **Output validation**: every `ToolResult` is checked by `ToolOutputValidator` (e.g. weather must contain `°C`/`humidity`/`rain`, files must contain `path`, communication must contain `sent/opened`). Invalid outputs become `Failure(retryable=true)` for retry/alternative.
- **Health**: `ToolHealthMonitor` tracks `avgLatency (EMA), failureRate, timeoutRate, successRate, lastSuccessAt`; `ToolRanker` prefers `health 40 + speed 15 + cost 10 + privacy 10 + local 10`.
- **Alternative fallback**: after `TOOL_MAX_ATTEMPTS=3` (500ms→8000ms), `ToolRunCoordinator.findAlternativeTool` tries the best alternative with same `permission/category`.

Implementation: [`ToolSpec.kt`](../../core/tools/src/main/java/io/androllm/core/tools/api/ToolSpec.kt), [`ToolRegistry.kt`](../../core/tools/src/main/java/io/androllm/core/tools/registry/ToolRegistry.kt), [`ToolsModule.kt`](../../core/tools/src/main/java/io/androllm/core/tools/di/ToolsModule.kt)
