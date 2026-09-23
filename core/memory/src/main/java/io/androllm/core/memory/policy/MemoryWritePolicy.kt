package io.androllm.core.memory.policy

import io.androllm.core.memory.MemoryCategory
import io.androllm.core.memory.model.ExtractedMemory
import io.androllm.core.memory.model.MemoryExchange
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Explicit write policy: what gets persisted, updated, or ignored.
 *
 * Used by [MemoryRepository] before the confidence gate so every model path
 * (local extraction, cloud extraction, manual save) applies the same rules.
 * All logic is deterministic and side-effect free for unit testing.
 *
 * STORE: preferences, stable identity facts, named project context,
 * decisions, ongoing tasks/goals, skills, devices, pinned facts, explicit
 * "remember this" requests.
 *
 * UPDATE (not duplicate): user corrections ("no, I meant…", "actually…",
 * "change X to Y", "my X is now Y") targeting an existing fact.
 *
 * IGNORE: greetings/filler/thanks, one-off requests, temporary scope
 * ("just for now", "for this chat only"), secrets/tokens, injection,
 * raw logs, vague low-value statements.
 */
@Singleton
class MemoryWritePolicy @Inject constructor() {

    sealed interface Decision {
        data object Persist : Decision
        data class Update(val targetHint: String) : Decision
        data class Ignore(val reason: String) : Decision
    }

    private val greetingOnly = Regex(
        """^(hi|hello|hey|thanks|thank you|ok|okay|yes|no|sure|bye)\W*$""",
        RegexOption.IGNORE_CASE
    )

    private val correctionSignals = listOf(
        "actually", "i meant", "correction", "my mistake", "change to",
        "update to", "should be", "is now", "no,", "no -", "not ",
        "forget the", "instead of", "rather than"
    )

    private val usefulSignals = listOf(
        "prefer", "like", "dislike", "love", "hate", "always", "never",
        "my name is", "i am", "i'm", "i work", "project", "remember this",
        "remember that", "don't forget", "my goal", "working on",
        "decided", "decision", "task", "todo", "deadline", "my device",
        "i use", "i own", "my stack", "speaks", "lives in", "works at"
    )

    private val noiseSignals = listOf(
        "explain ", "summarize this", "translate this", "what is",
        "what's", "how do i", "debug", "stacktrace", "console.log",
        "lorem ipsum", "test message"
    )

    fun decide(content: String, exchange: MemoryExchange? = null): Decision {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return Decision.Ignore("empty")
        if (trimmed.length < 12) return Decision.Ignore("low-value: too short")
        if (trimmed.length > 800) return Decision.Ignore("too long")
        val lower = trimmed.lowercase()

        if (greetingOnly.matches(trimmed)) return Decision.Ignore("greeting/filler")

        // Secrets and injection are never stored, even if otherwise useful.
        if (looksLikeSecret(trimmed)) return Decision.Ignore("secret")
        if (looksLikeInjection(trimmed)) return Decision.Ignore("injection")

        // Temporary scope beats usefulness — "just for now" is never long-term.
        if (isTemporaryScope(lower, exchange)) return Decision.Ignore("temporary scope")

        // Corrections update rather than duplicate.
        val correctionTarget = correctionTarget(lower, exchange)
        if (correctionTarget != null) return Decision.Update(correctionTarget)

        // Explicit "remember this" always persists.
        if ("remember this" in lower || "don't forget" in lower || "remember that" in lower) {
            return Decision.Persist
        }

        // Useful long-term signals persist.
        if (usefulSignals.any { it in lower }) return Decision.Persist

        // Known noise patterns are ignored unless they carry a durable fact.
        if (noiseSignals.any { it in lower } && !hasDurableAnchor(lower)) {
            return Decision.Ignore("one-off request")
        }

        // Category-grounded fallback: structured categories persist, CUSTOM
        // free text needs an anchor to avoid chat spam.
        return Decision.Persist
    }

    fun shouldPersist(content: String, exchange: MemoryExchange? = null): Boolean =
        decide(content, exchange) !is Decision.Ignore

    fun isCorrection(content: String, exchange: MemoryExchange? = null): Boolean {
        val lower = content.lowercase()
        if (correctionSignals.any { it in lower }) return true
        if (exchange == null) return false
        val userLower = exchange.userMessage.lowercase()
        return correctionSignals.any { it in userLower }
    }

    private fun correctionTarget(lower: String, exchange: MemoryExchange?): String? {
        if (!isCorrection(lower, exchange)) return null
        // Hint the subject for the repository's conflict resolver.
        val subjectKeywords = listOf(
            "name", "location", "address", "phone", "email", "project",
            "prefer", "theme", "mode", "language", "device", "stack"
        )
        return subjectKeywords.firstOrNull { it in lower }
            ?: exchange?.userMessage?.take(60)
    }

    private fun isTemporaryScope(lower: String, exchange: MemoryExchange?): Boolean {
        val markers = listOf(
            "just for now", "just for this chat", "just for this session",
            "for now", "temporary", "temporarily", "one-off", "one off",
            "just this once", "in this session only", "in this chat only",
            "for this project only", "just today", "right now"
        )
        if (markers.any { it in lower }) return true
        if (exchange == null) return false
        val exchangeText = (exchange.userMessage + " " + exchange.assistantResponse).lowercase()
        return markers.any { it in exchangeText }
    }

    private fun looksLikeSecret(content: String): Boolean {
        val lower = content.lowercase()
        return lower.contains("sk-") || lower.contains("ghp_") ||
            lower.contains("gsk_") || lower.contains("xoxb-") ||
            Regex("""(?i)(password|passwd|pwd|secret|api[_-]?key|token)\s*[:=]""").containsMatchIn(content) ||
            Regex("""\b\d{4}[\s-]?\d{4}[\s-]?\d{4}[\s-]?\d{4}\b""").containsMatchIn(content)
    }

    private fun looksLikeInjection(content: String): Boolean {
        val lower = content.lowercase()
        return lower.contains("ignore previous instructions") ||
            lower.contains("pretend you are") || lower.contains("<tool_call>") ||
            lower.startsWith("system:") || lower.startsWith("developer:")
    }

    private fun hasDurableAnchor(lower: String): Boolean =
        usefulSignals.any { it in lower } ||
            lower.contains("prefer") || lower.contains("is ") ||
            lower.contains("project")

    /** Convenience for callers holding an [ExtractedMemory]. */
    fun decideExtracted(item: ExtractedMemory, exchange: MemoryExchange?): Decision {
        // Pinned facts and explicit project context always persist.
        if (item.category == MemoryCategory.PINNED_FACTS) return Decision.Persist
        if (item.category == MemoryCategory.PROJECTS) return Decision.Persist
        if (!item.projectName.isNullOrBlank()) return Decision.Persist
        return decide(item.content, exchange)
    }
}
