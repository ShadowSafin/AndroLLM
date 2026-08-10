# Accessibility Automation

How the assistant operates **other apps** — reading screens, tapping, typing,
scrolling and gesturing — when no native API or intent exists.

> **Principle:** official Android APIs and intents are always preferred. The
> accessibility service is the *fallback* layer for third-party apps
> (WhatsApp, YouTube, Uber, …) that expose no native tool.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  AccessibilityService (system-registered, foreground-only)   │
│  ── produces a live snapshot of the app's view hierarchy     │
└──────────────────────────────┬───────────────────────────────┘
                               ▼
┌──────────────────────────────────────────────────────────────┐
│  AccessibilityController                                     │
│  • node tree snapshot + normalization                       │
│  • element finding (text / description / id)                │
│  • actions: click, long-press, double-tap, drag, swipe,     │
│    scroll, pinch, type, navigate (back/home/recents/…)      │
└───────────────┬──────────────────────────────┬──────────────┘
                │                              │
                ▼                              ▼
┌────────────────────────────┐   ┌──────────────────────────────┐
│  GestureExecutor           │   │  Planner                     │
│  GestureDescription paths  │   │  LLM planning (local model)  │
│  (single & multi-finger)   │   │  or heuristic rules fallback │
└────────────────────────────┘   └──────────────────────────────┘
```

All automation is exposed to the agent as normal tools (see
[Tool Catalog](tools.md) → UI Automation).

---

## What the Engine Can See

From the accessibility node tree, the engine detects:

- Visible text and content descriptions
- Clickable nodes, editable fields, checkboxes, switches, sliders
- Lists / RecyclerViews / LazyColumns (scrolled into view on demand)
- Dialogs, bottom sheets, popups
- WebViews (best-effort), multi-window and split-screen awareness
- Foreground app identity

Each element is classified (`UiElementClassifier`) and findable by text,
description, or stable path — so `ui_click(text="Send")` works without
coordinates.

---

## Gestures

`GestureExecutor` builds `GestureDescription` paths with correct timing:

| Gesture | Notes |
|---|---|
| Tap | 1 finger, short press |
| Long-press | ~600 ms hold |
| Double-tap | two taps in quick succession |
| Drag | 1 finger, slow move |
| Swipe | 1 finger, fast move (scrolling/paging) |
| Pinch | **2 fingers**, pinch-in / pinch-out (zoom) |
| Scroll | directional, settles at snap points |

`ui_gesture` exposes these directly (type + from/to coordinates), while
`ui_click`, `ui_type`, `ui_scroll`, `ui_swipe`, and `ui_navigate` cover the
common cases with semantic arguments.

---

## Planning Steps

For multi-step tasks (`ui_run`), the assistant picks one of two planners:

1. **LLM planning** — the loaded local model picks each step from the
   current screen snapshot (enabled in Settings → UI Automation)
2. **Heuristic rules** — deterministic fallback (launch app → find field →
   type → press go) that works without a model loaded

A `SafetyClassifier` labels steps that send, pay, book, delete or install —
with "confirm high-risk steps" enabled, those ask for approval before
executing.

---

## Settings → UI Automation

| Setting | Effect |
|---|---|
| Accessibility service | Enable the system service (deep link to system settings); shows live status |
| Scroll into view | Auto-scroll lists until the target element is visible before tapping |
| LLM planning | Use the local model to pick each step (falls back to rules) |
| Confirm high-risk steps | Ask before anything that sends, pays, books, deletes or installs |
| Developer mode | Record execution trees, node dumps and gesture logs |

---

## QR Scanning

`scan_qr` reads QR codes from a captured screenshot or an image using ZXing —
useful for wifi passwords, URLs, and contact cards the user points the
camera at.

---

## Safety

- The service only runs **while requested** (tool calls), never silently
  screenshots or logs
- High-risk UI steps are confirmation-gated
- Every action is trace-logged (Developer → Tool Debug)
- Developer mode is off by default — node dumps are only recorded when
  explicitly enabled

---

## See Also

- [Agent Platform](agent-platform.md) — shared safety gates
- [Tool Catalog](tools.md) — the `ui_*` tool family
- [Workflow Engine](workflow-engine.md) — `ui_run` inside multi-step tasks
