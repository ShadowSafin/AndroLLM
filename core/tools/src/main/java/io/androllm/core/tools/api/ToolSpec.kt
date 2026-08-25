package io.androllm.core.tools.api

import android.Manifest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Logical capability a tool needs from the user. Each permission maps 1:1 to
 * a toggle in Settings → Automation, so the user can block entire tool
 * categories without touching the Android runtime permissions. Android runtime
 * permissions are enforced separately inside each tool (with clear failure
 * messages when they are missing).
 */
enum class ToolPermission(val displayName: String, val description: String) {
    WEATHER("Weather", "Fetch current weather and forecasts"),
    SEARCH("Web Search", "Search the web for information"),
    SMS("SMS", "Send text messages (always confirmed)"),
    CALLS("Phone Calls", "Make phone calls (always confirmed)"),
    EMAIL("Email", "Compose and open emails (always confirmed)"),
    MAPS("Maps", "Open navigation and search nearby places"),
    CALENDAR("Calendar", "Create and read calendar events"),
    ALARMS("Alarms & Reminders", "Set alarms and reminders"),
    CLIPBOARD("Clipboard", "Copy text to the clipboard"),
    NOTIFICATIONS("Notifications", "Read active notifications"),
    FLASHLIGHT("Flashlight", "Toggle the camera flashlight"),
    BLUETOOTH("Bluetooth", "Enable or disable Bluetooth"),
    WIFI("Wi-Fi", "Enable or disable Wi-Fi"),
    MUSIC("Music", "Control media playback"),
    APPS("App Launcher", "Launch installed apps"),
    CONTACTS("Contacts", "Look up contacts"),
    CAMERA("Camera", "Open the camera"),
    SCREENSHOT("Screenshot", "Capture the screen"),
    SHARE("Share", "Share text with other apps"),
    SYSTEM("System", "Change system settings"),
    ACCESSIBILITY("UI Automation", "Control other apps through the accessibility service"),
    MCP("MCP Servers", "Tools provided by connected MCP servers"),
    NOTES("Notes", "Save, read and delete notes"),
    FILES("Files & Exports", "List files, export PDF/Markdown"),
    VOICE_RECORDER("Voice Recorder", "Record voice notes"),
    QR("QR Scanner", "Read QR codes"),
    GITHUB("GitHub", "Search GitHub repositories and releases"),
    CALCULATOR("Calculator", "Math, unit and currency conversion"),
    TRANSLATION("Translation", "Open Google Translate"),
    MEDIA("Media", "Open gallery and media apps"),
    DEVICE("Device Info", "Read device state and information"),
    LOCATION("Location", "Read the device's current location")
}

/**
 * Android runtime permissions backing this logical capability, if any. These
 * are declared in the tools module manifest and must be granted at runtime
 * before the tool can run — the confirmation card requests them on approve,
 * and Settings → Automation offers a grant button for pre-granting.
 */
fun ToolPermission.runtimePermissions(): List<String> = when (this) {
    ToolPermission.SMS -> listOf(Manifest.permission.SEND_SMS)
    ToolPermission.CONTACTS -> listOf(Manifest.permission.READ_CONTACTS)
    ToolPermission.CALLS -> listOf(Manifest.permission.CALL_PHONE)
    ToolPermission.CALENDAR -> listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    ToolPermission.VOICE_RECORDER -> listOf(Manifest.permission.RECORD_AUDIO)
    ToolPermission.LOCATION -> listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    else -> emptyList()
}

/** Coarse grouping used by the settings UI. */
enum class ToolCategory(val displayName: String) {
    INFORMATION("Information"),
    COMMUNICATION("Communication"),
    DEVICE("Device"),
    MEDIA("Media & Apps"),
    PRODUCTIVITY("Productivity")
}

/**
 * Backend where a tool can run. Local = on-device LiteRT/utility code,
 * Cloud = via LiteLLM gateway / provider. Most tools are local; some
 * (e.g. Github, large web search) are cloud-agnostic but still work
 * locally via network.
 */
enum class ToolBackend {
    LOCAL,
    CLOUD
}

/**
 * Static, discoverable description of a tool. Everything the planner needs to
 * know about a tool lives here (plus [Tool.execute]): the LLM sees only
 * [name], [description] and [parameters], never the implementation.
 */
data class ToolSpec(
    /** Snake-case identifier used by the LLM (e.g. "get_weather"). */
    val name: String,
    /** One-two sentence description of what the tool does. */
    val description: String,
    /** JSON Schema describing [Tool.execute]'s argument object. */
    val parameters: JsonObject = buildJsonObject { },
    /** Logical capability toggle; null = never gated by settings. */
    val permission: ToolPermission? = null,
    /** True when execution must be confirmed by the user first. */
    val requiresConfirmation: Boolean = false,
    val category: ToolCategory = ToolCategory.INFORMATION,
    /**
     * Natural-language action phrase used by confirmations, e.g.
     * "send the SMS to Mom". Falls back to the tool name when blank.
     */
    val confirmationPrompt: String = "",
    /**
     * Execution budget override in ms. Null uses the executor's default
     * (20s). Multi-step tools like the UI-automation runner need far more.
     */
    val executionTimeoutMs: Long? = null,
    /**
     * Natural-language task phrases this tool handles (e.g. "math",
     * "battery", "web search"). The [io.androllm.core.tools.router.ToolRouter]
     * matches the user request against these to decide which tools the LLM
     * may see for a turn — the spec's "supported_tasks" on every tool.
     */
    val supportedTasks: List<String> = emptyList(),
    /**
     * True when the tool is a pure read (no side effects) and its output may
     * be cached briefly and reused when the SAME call is re-requested (e.g.
     * a regenerated answer re-running a web search). Never true for tools
     * that send, write, or change device state.
     */
    val cacheable: Boolean = false,
    /**
     * Backends where this tool is supported. Requirement 4: supported backends
     * + availability + local/cloud-only. Default: local + cloud (both).
     * Tools that are cloud-only (e.g. some LLM-dependent transforms) can set
     * setOf(CLOUD); device-only tools set setOf(LOCAL).
     */
    val supportedBackends: Set<ToolBackend> = setOf(ToolBackend.LOCAL, ToolBackend.CLOUD),
    /**
     * Whether the tool is currently available on this device (e.g. hardware
     * sensor present, permission declared). Evaluated at registration time;
     * unavailable tools are still registered but filtered from the planner
     * when [availableOnDevice] is false.
     */
    val availableOnDevice: Boolean = true,
    /**
     * Whether this tool can run with a local LiteRT model as first-class
     * citizen (prompt-based emulation). False = cloud-only tool.
     */
    val worksLocally: Boolean = true,
    /** True when the tool is cloud-only (never runs locally). */
    val isCloudOnly: Boolean = false,
    /** Estimated latency in ms (for ranking: faster preferred when accuracy equal). */
    val estimatedLatencyMs: Long = 2000L,
    /** Privacy level: higher means more sensitive data. */
    val privacyLevel: PrivacyLevel = PrivacyLevel.LOCAL,
    /** Cost hint: FREE vs NETWORK vs PAID. */
    val cost: ToolCost = ToolCost.FREE,
    /** Known failure modes for recovery planning (timeout, rate_limit, network, auth, malformed). */
    val failureModes: List<String> = emptyList(),
    /** Tool dependencies: other tool names that must have succeeded before this can run (e.g. note_save depends on search_web). */
    val dependencies: List<String> = emptyList(),
    /** Declared capabilities for capability-aware ranking. */
    val capabilities: List<String> = emptyList()
) {
    /** Convenience: does this tool support the given backend? */
    fun supports(backend: ToolBackend): Boolean = backend in supportedBackends

    /** Availability check for the planner: must be enabled and available on device. */
    val isAvailable: Boolean get() = availableOnDevice
}

/** Privacy level for tool execution — used in ranking (LOCAL preferred over CLOUD). */
enum class PrivacyLevel(val rank: Int) {
    LOCAL(0),        // purely on-device, no data leaves device
    NETWORK(1),      // network call but anonymized (e.g. weather)
    CLOUD(2),        // cloud provider involved
    SENSITIVE(3)     // personal data (contacts, SMS, location)
}

/** Cost hint for ranking — cheaper preferred. */
enum class ToolCost(val rank: Int) {
    FREE(0),
    NETWORK(1),
    PAID(2)
}
