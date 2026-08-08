package io.androllm.feature.voice.commands

/**
 * A voice command that can be executed locally — no LLM round-trip needed.
 * Everything the router does not match goes to the model.
 *
 * Commands are intentionally narrow and side-effect-aware; the executor in
 * [io.androllm.feature.voice.service.VoiceAssistantService] handles each one
 * directly without round-tripping through the chat engine.
 */
sealed interface VoiceCommand {
    /** "open settings" / "show settings" — navigates to Settings. */
    data object OpenSettings : VoiceCommand

    /** "open models" / "open downloads" — navigates to the Models screen. */
    data object OpenModels : VoiceCommand

    /** "delete conversation" / "clear chat" — clears the active conversation. */
    data object DeleteConversation : VoiceCommand

    /** "summarize chat" — produces a summary of the current conversation. */
    data object SummarizeChat : VoiceCommand

    /** "start new chat" — drops the active conversation and starts fresh. */
    data object StartNewChat : VoiceCommand

    /** "stop speaking" / "shut up" / "be quiet" — interrupts the current turn. */
    data object StopSpeaking : VoiceCommand

    /** "mute" / "mute yourself" — temporarily suppresses the assistant. */
    data object Mute : VoiceCommand

    /** "unmute" / "unmute yourself" — resumes listening after [Mute]. */
    data object Unmute : VoiceCommand

    /** "enable offline mode" — flips [io.androllm.core.voice.model.VoiceSettings.offlineOnly] on. */
    data object EnableOfflineMode : VoiceCommand

    /** "disable offline mode" — flips offline-only off. */
    data object DisableOfflineMode : VoiceCommand

    /** "disable voice" / "turn off voice" — stops the foreground assistant. */
    data object DisableVoice : VoiceCommand

    /** "switch theme" / "toggle theme" — cycles LIGHT → DARK → SYSTEM. */
    data object SwitchTheme : VoiceCommand
}

/**
 * Pure string → command matching. Only clear, deterministic phrases map to a
 * local command; ambiguous speech falls through to the LLM.
 *
 * Matchers are intentionally lenient ("mute yourself", "please mute", "go
 * mute" all resolve) because the user does not need to memorize exact phrasings.
 */
object VoiceCommandRouter {

    fun match(transcript: String): VoiceCommand? {
        val t = transcript.trim().lowercase()
            .replace(Regex("[.,!?]+$"), "")
            .trim()
        if (t.isEmpty()) return null

        // ── Navigation ────────────────────────────────────────────────────────
        if (t.matchesNavigation("open settings", "go to settings", "show settings",
                "open the settings")) return VoiceCommand.OpenSettings

        if (t.matchesNavigation("open models", "go to models", "show models",
                "open the models", "models screen", "open downloads",
                "show downloads", "open downloads screen")) return VoiceCommand.OpenModels

        // ── Conversation control ──────────────────────────────────────────────
        if (t.matchesAny("delete conversation", "delete this conversation",
                "clear conversation", "clear the conversation", "delete chat")) {
            return VoiceCommand.DeleteConversation
        }

        if (t.matchesAny("start new chat", "start a new chat", "new chat",
                "new conversation", "start over", "reset chat")) {
            return VoiceCommand.StartNewChat
        }

        if (t.matchesAny("summarize chat", "summarize the chat",
                "summarize conversation", "summarize this conversation",
                "summarize the conversation", "summarize")) {
            return VoiceCommand.SummarizeChat
        }

        // ── Live turn control ─────────────────────────────────────────────────
        if (t.matchesAny("stop speaking", "shut up", "be quiet", "quiet",
                "stop talking", "cancel", "stop that", "enough")) {
            return VoiceCommand.StopSpeaking
        }

        if (t.matchesAny("mute", "mute yourself", "go quiet", "silence",
                "be silent", "mute the assistant")) {
            return VoiceCommand.Mute
        }

        if (t.matchesAny("unmute", "unmute yourself", "resume listening",
                "wake up", "i'm done", "im done")) {
            return VoiceCommand.Unmute
        }

        // ── Settings toggles ──────────────────────────────────────────────────
        if (t.matchesAny("enable offline mode", "go offline", "use offline",
                "offline mode", "switch to offline")) {
            return VoiceCommand.EnableOfflineMode
        }

        if (t.matchesAny("disable offline mode", "go online", "use cloud",
                "online mode", "switch to online")) {
            return VoiceCommand.DisableOfflineMode
        }

        if (t.matchesAny("disable voice", "turn off voice", "stop listening",
                "turn off the assistant", "stop the assistant",
                "disable the assistant")) {
            return VoiceCommand.DisableVoice
        }

        if (t.matchesAny("switch theme", "toggle theme", "change theme",
                "dark mode", "light mode", "switch to dark", "switch to light")) {
            return VoiceCommand.SwitchTheme
        }

        return null
    }

    private fun String.matchesAny(vararg phrases: String): Boolean =
        phrases.any { this == it || this.startsWith("$it ") || this.endsWith(" $it") || contains(" $it ") }

    private fun String.matchesNavigation(vararg phrases: String): Boolean =
        phrases.any { this == it || this.startsWith("$it ") || this.endsWith(" $it") }
}