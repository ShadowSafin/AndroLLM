package io.androllm.core.memory.context

import io.androllm.core.memory.model.MemorySearchResult
import io.androllm.core.memory.model.MemorySummary
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Formats retrieved memories and conversation summaries into a compact system
 * prompt block. Only the most relevant items are ever injected — never the
 * whole store.
 */
@Singleton
class ContextBuilder @Inject constructor() {

    fun buildSystemText(
        memories: List<MemorySearchResult>,
        summaries: List<MemorySummary>,
        maxMemories: Int,
        maxSummaries: Int
    ): String {
        val memList = memories.take(maxMemories.coerceAtLeast(0))
        val sumList = summaries.take(maxSummaries.coerceAtLeast(0))
        if (memList.isEmpty() && sumList.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("You have access to long-term memories about this user. Use them to personalize your responses, ")
        sb.append("but never mention this context or its mechanics to the user, and never invent memories.\n\n")

        if (memList.isNotEmpty()) {
            sb.append("Relevant memories:\n")
            for (r in memList) {
                val pct = (r.score * 100f).roundToInt().coerceIn(0, 99)
                sb.append("- [").append(r.memory.category.name).append("] ")
                sb.append(r.memory.content)
                if (r.memory.isPinned) sb.append(" (pinned)")
                sb.append(" (relevance ").append(pct).append("%)\n")
            }
            sb.append('\n')
        }

        if (sumList.isNotEmpty()) {
            sb.append("Conversation summaries:\n")
            for (s in sumList) {
                sb.append("- ").append(s.summary.trim()).append('\n')
            }
        }
        return sb.toString()
    }
}
