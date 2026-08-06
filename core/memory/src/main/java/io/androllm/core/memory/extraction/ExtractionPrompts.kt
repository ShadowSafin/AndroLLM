package io.androllm.core.memory.extraction

import io.androllm.core.memory.model.MemoryExchange

/**
 * Shared extraction prompt, used identically by the local and the cloud
 * intelligence backing so the same JSON contract works on every provider.
 */
object ExtractionPrompts {

    val SYSTEM_INSTRUCTION: String = """
        You are the long-term memory module of an on-device AI assistant.
        From the conversation exchange below, extract ONLY durable, long-term facts worth remembering across sessions.

        STORE these kinds of facts:
        - Identity: who the user is (name, role, background)
        - Preferences: likes, dislikes, preferred tone/format, defaults
        - Projects: named projects, their purpose, stack, current status
        - Goals: things the user is working toward
        - Skills: what the user knows or is learning
        - Programming languages and Frameworks the user works with
        - Devices the user owns or develops for
        - Pinned facts: stable facts stated with certainty
        - Developer notes: technical decisions, gotchas, solutions

        IGNORE:
        - Greetings, small talk, pleasantries
        - Temporary or one-off requests ("explain X", "summarize this")
        - Content that only makes sense inside this specific conversation
        - Repetition of facts already stated

        Rules:
        - Write each memory as ONE short, self-contained statement, present tense, third person ("User prefers ...").
        - Never invent facts. Never repeat facts from the system context.
        - importance: 1 (trivial) to 5 (critical).
        - If nothing is worth storing, return "memories": [].
        - Respond ONLY with the JSON object. No prose, no markdown.
    """.trimIndent()

    fun buildUserContent(exchange: MemoryExchange): String {
        val sb = StringBuilder()
        sb.append("CONVERSATION (latest exchange):\n")
        sb.append("user: ").append(exchange.userMessage.take(1200)).append('\n')
        sb.append("assistant: ").append(exchange.assistantResponse.take(1600)).append('\n')
        if (exchange.recentMessages.isNotEmpty()) {
            sb.append("\nRECENT CONTEXT (older turns):\n")
            for ((role, content) in exchange.recentMessages.takeLast(6)) {
                sb.append(role).append(": ").append(content.take(300)).append('\n')
            }
        }
        return sb.toString()
    }
}