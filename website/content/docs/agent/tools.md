# Tool Catalog

Complete reference for every built-in tool in the agent platform. Tools are
registered in `core/tools` (plus `core/accessibility` for UI automation) via
Hilt multibinding and exposed to the planner under their `name`.

> **Notation:** 🔒 = requires user confirmation before executing (SMS, calls,
> email). Permissions map to toggles in Settings → Automation. Categories
> match the settings grouping (Information, Communication, Device, Media &
> Apps, Productivity).

---

## Information

| Tool | Name | Arguments | Permission |
|---|---|---|---|
| Weather | `get_weather` | `location` | Weather |
| Web search | `search_web` | `query` | Web Search |
| Notifications | `read_notifications` | — | Notifications |
| Device info | `get_device_info` | — | Device Info |
| Calculator | `calculate` | `expression` | Calculator |
| Unit converter | `convert_units` | `value`, `from`, `to` | Calculator |
| Currency | `convert_currency` | `amount`, `from`, `to` | Calculator |
| GitHub | `github` | `action` (`repos` / `releases`), `query`, `repo?` | GitHub |
| Translate | `open_translation` | `text`, `target?` | Translation |

---

## Communication

| Tool | Name | Arguments | Permission |
|---|---|---|---|
| SMS 🔒 | `send_sms` | `phone` (number *or* contact name), `message` | SMS |
| Phone call 🔒 | `make_call` | `phone` | Phone Calls |
| Email 🔒 | `send_email` | `to`, `subject`, `body` | Email |
| Contacts | `find_contacts` | `name` | Contacts |
| Share | `share_text` | `text`, `title?` | Share |

**Recipient resolution:** `send_sms` (and friends) accept a contact name
("Mom") or a formatted number. Non-numeric recipients are resolved against
the contacts provider automatically before sending; long messages are split
into multipart SMS. If a name cannot be resolved, the tool fails with clear
guidance instead of silently dropping the message.

---

## Device

| Tool | Name | Arguments | Permission |
|---|---|---|---|
| Battery | `get_battery` | — | Device Info |
| Running apps | `get_running_apps` | — | Device Info |
| Volume | `set_volume` | `level` (0–100) | System |
| Flashlight | `set_flashlight` | `on` | Flashlight |
| Bluetooth | `set_bluetooth` | `enabled` | Bluetooth |
| Wi-Fi | `set_wifi` | `enabled` | Wi-Fi |
| Screenshot | `take_screenshot` | — | Screenshot |

---

## Media & Apps

| Tool | Name | Arguments | Permission |
|---|---|---|---|
| App launcher | `launch_app` | `app` (name or package) | App Launcher |
| Camera | `open_camera` | — | Camera |
| Gallery | `open_gallery` | — | Media |
| Music | `control_music` | `action` (play/pause/next/prev), `track?` | Music |
| Voice recorder | `record_voice` | `duration?` | Voice Recorder |

---

## Productivity

| Tool | Name | Arguments | Permission |
|---|---|---|---|
| Clipboard | `copy_to_clipboard` | `text` | Clipboard |
| Reminder | `create_reminder` | `text`, `time?` | Alarms & Reminders |
| Alarm | `set_alarm` | `time` (natural language) | Alarms & Reminders |
| Calendar | `calendar` | `action` (`create` / `read`), `title`, `start?` | Calendar |
| Navigation | `open_navigation` | `destination` | Maps |
| Nearby places | `search_places` | `query`, `location?` | Maps |
| Notes | `note_save` / `note_list` / `note_get` / `note_delete` | `title`, `content`, `id?` | Notes |
| PDF export | `export_pdf` | `title`, `content` | Files & Exports |
| Markdown export | `export_markdown` | `title`, `content` | Files & Exports |
| Downloads | `list_downloads` | — | Files & Exports |
| App files | `list_app_files` | — | Files & Exports |
| Variable set | `variable_set` | `key`, `value` | — (always available) |
| Variable get | `variable_get` | `key` | — (always available) |

---

## UI Automation (Accessibility)

See [Accessibility Automation](accessibility-automation.md) for details.

| Tool | Name | Arguments |
|---|---|---|
| Read screen | `ui_read_screen` | — |
| Tap | `ui_click` | `text` / `description` / `id` |
| Type | `ui_type` | `text`, `into?` |
| Scroll | `ui_scroll` | `direction` |
| Swipe | `ui_swipe` | `from`, `to` |
| Gesture | `ui_gesture` | `type` (tap/long-press/double-tap/drag/swipe/pinch), `from`, `to` |
| Navigate | `ui_navigate` | `action` (back/home/recents/…) |
| Multi-step task | `ui_run` | `goal` (plain-language task) |
| QR scanner | `scan_qr` | — |

---

## MCP Remote Tools

Every tool imported from a connected MCP server becomes available as
`mcp_<server>_<tool>` and behaves exactly like a built-in tool (same gates,
same planning, same trace logging). See [MCP Servers](mcp.md).

---

## Permissions

Each tool maps to a logical `ToolPermission` that drives its toggle in
Settings → Automation and (where needed) the Android runtime permissions:

| Permission | Runtime permission(s) |
|---|---|
| SMS | `SEND_SMS` |
| Contacts | `READ_CONTACTS` |
| Phone Calls | `CALL_PHONE` |
| Calendar | `READ_CALENDAR`, `WRITE_CALENDAR` |
| Voice Recorder | `RECORD_AUDIO` |

These are requested lazily — via the confirmation card on approve, or via
the grant buttons in Settings → Automation. Camera/QR check `CAMERA`
*inside the tool* (grant it through Android settings); the tool fails with
guidance when it is missing.
