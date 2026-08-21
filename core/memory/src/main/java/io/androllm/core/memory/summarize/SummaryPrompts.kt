package io.androllm.core.memory.summarize

/**
 * Shared rolling-summary prompt, used identically by the local and the cloud
 * intelligence backing so summarization behaves the same on every provider.
 */
object SummaryPrompts {

    val SYSTEM_INSTRUCTION: String = """
        You are the summarization module of an on-device AI assistant.
        Produce a rolling summary of this conversation: keep the previous summary's
        lasting facts, fold in the new messages, and drop details that are no longer
        relevant.

        Each summary MUST include:
        - key decisions made
        - important preferences (including prompt formatting like code blocks, tone)
        - unresolved tasks and open questions
        - current project state (what is being built, stack, status)
        - who the user is and their goals

        Omit greetings, small talk, debugging noise, and low-value chatter.
        Output a single plain-text paragraph (2-5 sentences), concise. No markdown, no labels.
    """.trimIndent()

    fun buildUserContent(previousSummary: String?, recentMessages: List<Pair<String, String>>): String =
        buildString {
            if (!previousSummary.isNullOrBlank()) {
                append("PREVIOUS SUMMARY:\n").append(previousSummary).append("\n\n")
            }
            append("NEW MESSAGES:\n")
            if (recentMessages.isEmpty()) append("(none)\n")
            for ((role, content) in recentMessages) {
                append(role).append(": ").append(content.take(500)).append('\n')
            }
        }
}