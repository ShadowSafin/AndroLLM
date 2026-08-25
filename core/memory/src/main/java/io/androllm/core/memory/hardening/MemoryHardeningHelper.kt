package io.androllm.core.memory.hardening

import io.androllm.core.memory.MemoryCategory
import io.androllm.core.memory.model.ExtractedMemory
import io.androllm.core.memory.model.Memory
import io.androllm.core.memory.model.MemoryExchange
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Production hardening utilities: confidence scoring, temporary-context detection,
 * contradiction detection, deterministic dedup helpers. All logic is deterministic
 * and side-effect free so unit tests can verify exact behavior.
 */
@Singleton
class MemoryHardeningHelper @Inject constructor() {

    // ── Temporary / one-off detection ────────────────────────────────────────
    private val temporaryPatterns = listOf(
        Regex("""\b(just for (now|this (time|session|chat)|today)|for now|temporary|temporarily|this (time|session) only|one[-\s]?off|just this once)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(remind me|explain|summarize|translate|debug|test|demo|example)\b.*\b(for now|quick|once)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(playing|listening|watching) .+ right now\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(current (task|request|question)|in this (chat|conversation) only)\b""", RegexOption.IGNORE_CASE)
    )

    private val shortLivedPreferencePatterns = listOf(
        Regex("""\b(use|prefer).{0,30}\b(for (now|this (project|task)|today)|just (today|now))\b""", RegexOption.IGNORE_CASE)
    )

    fun isTemporaryContext(content: String, exchange: MemoryExchange? = null): Boolean {
        val lower = content.lowercase()
        if (temporaryPatterns.any { it.containsMatchIn(lower) }) return true
        if (shortLivedPreferencePatterns.any { it.containsMatchIn(lower) }) return true
        // Exchange-level: if user says "just for this chat" the extracted preference is short-lived
        if (exchange != null) {
            val exchangeText = (exchange.userMessage + " " + exchange.assistantResponse).lowercase()
            if (exchangeText.contains("just for this chat") || exchangeText.contains("just for now") || exchangeText.contains("one-off") || exchangeText.contains("one off")) {
                return true
            }
            // One-off request detection: user asked to "explain X" and memory is that explanation (not a durable preference)
            if (exchangeText.contains("explain") && content.length < 80 && !content.contains("prefer") && !content.contains("is")) {
                return true
            }
        }
        return false
    }

    // ── Confidence scoring ───────────────────────────────────────────────────
    /**
     * Confidence 0.0..1.0 that this extracted memory is worth persisting long-term.
     * Used as gate before commit: <0.55 is dropped, 0.55..0.75 is low (only if not temporary),
     * >=0.75 is high.
     */
    fun confidenceScore(extracted: ExtractedMemory, exchange: MemoryExchange? = null): Double {
        var score = 0.5
        val content = extracted.content.trim()
        val lower = content.lowercase()
        val len = content.length

        // Length: concise but not trivial
        when {
            len < 15 -> score -= 0.3
            len in 15..40 -> score += 0.1
            len in 41..140 -> score += 0.2
            len > 280 -> score -= 0.1
        }

        // Category: PREFERENCES/IDENTITY/PINNED_FACTS are higher confidence if grounded
        when (extracted.category) {
            MemoryCategory.PREFERENCES, MemoryCategory.IDENTITY, MemoryCategory.PINNED_FACTS -> score += 0.15
            MemoryCategory.CUSTOM -> score -= 0.05
            else -> score += 0.05
        }

        // Importance as proxy for extractor confidence
        score += (extracted.importance.coerceIn(1, 5) - 3) * 0.07

        // Grounding: must appear in exchange
        if (exchange != null) {
            val grounded = isGroundedForConfidence(content, exchange)
            if (!grounded) score -= 0.35 else score += 0.1
        }

        // Temporary penalty
        if (isTemporaryContext(content, exchange)) score -= 0.4

        // Project-tagged memories are more durable
        if (!extracted.projectName.isNullOrBlank()) score += 0.05

        // Tags present indicates well-formed extraction
        if (extracted.tags.isNotEmpty()) score += 0.03

        // Low-value phrases
        if (lower in setOf("hello", "thanks", "ok", "debug log")) score -= 0.5

        return score.coerceIn(0.0, 1.0)
    }

    private fun isGroundedForConfidence(content: String, exchange: MemoryExchange): Boolean {
        // Manual saves via UI use empty exchange — consider grounded (not hallucinated)
        if (exchange.userMessage.isBlank() && exchange.assistantResponse.isBlank() && exchange.recentMessages.isEmpty()) return true
        val words = content.lowercase().split(Regex("""\W+""")).filter { it.length > 3 }.toSet()
        if (words.size < 2) return true
        val exchangeText = (exchange.userMessage + " " + exchange.assistantResponse + " " + exchange.recentMessages.joinToString(" ") { it.second }).lowercase()
        val overlap = words.count { it in exchangeText }
        return overlap >= (words.size * 0.3).coerceAtLeast(1.0)
    }

    fun shouldCommit(extracted: ExtractedMemory, exchange: MemoryExchange?): Boolean {
        if (isTemporaryContext(extracted.content, exchange)) return false
        val conf = confidenceScore(extracted, exchange)
        // Threshold: 0.55 for general, 0.60 for CUSTOM low-importance
        val threshold = if (extracted.category == MemoryCategory.CUSTOM && extracted.importance <= 2) 0.62 else 0.55
        return conf >= threshold
    }

    // ── Contradiction detection ──────────────────────────────────────────────
    private val contradictionPairs = listOf(
        Pair("prefers dark mode", "prefers light mode"),
        Pair("prefers light mode", "prefers dark mode"),
        Pair("likes", "dislikes"),
        Pair("enables", "disables")
    )

    fun isContradictory(a: String, b: String): Boolean {
        val al = a.lowercase()
        val bl = b.lowercase()
        // Exact opposite preferences with same subject
        for ((p1, p2) in contradictionPairs) {
            if ((p1 in al && p2 in bl) || (p2 in al && p1 in bl)) {
                // Check subject overlap: share at least 2 significant words
                val aWords = al.split(Regex("""\W+""")).filter { it.length > 3 }.toSet()
                val bWords = bl.split(Regex("""\W+""")).filter { it.length > 3 }.toSet()
                if (aWords.intersect(bWords).size >= 1) return true
            }
        }
        // Generic: same 3+ word prefix but opposite trailing adjective
        if (al.length > 20 && bl.length > 20) {
            val aPrefix = al.split(" ").take(4).joinToString(" ")
            val bPrefix = bl.split(" ").take(4).joinToString(" ")
            if (aPrefix == bPrefix) {
                val aSuffix = al.substringAfter(aPrefix).trim()
                val bSuffix = bl.substringAfter(bPrefix).trim()
                if (aSuffix.isNotEmpty() && bSuffix.isNotEmpty() && aSuffix != bSuffix) {
                    // e.g., "User prefers short prompts" vs "User prefers long prompts"
                    if ((aSuffix.contains("short") && bSuffix.contains("long")) || (aSuffix.contains("dark") && bSuffix.contains("light"))) {
                        return true
                    }
                }
            }
        }
        return false
    }

    fun findContradictions(newContent: String, existing: List<Memory>): List<Memory> {
        return existing.filter { isContradictory(newContent, it.content) }
    }

    // ── Deterministic dedup helper ───────────────────────────────────────────
    fun normalizeForDedupe(content: String): String {
        return content.lowercase()
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""[^\p{L}\p{N}\s]"""), "")
            .trim()
    }

    // ── Evidence / recency resolver ──────────────────────────────────────────
    /**
     * Picks winner between conflicting memories using timestamp, confidence, and evidence.
     * Newer + higher importance wins; if tie, longer grounded content wins.
     */
    fun resolveConflict(newContent: String, newImportance: Int, newTimestamp: Long, existing: Memory): Memory? {
        // If existing is newer and higher priority, keep existing
        if (existing.updatedAt > newTimestamp && existing.effectivePriority >= newImportance) {
            return existing
        }
        // If new is newer and similar or higher importance, new wins
        if (newTimestamp >= existing.updatedAt && newImportance >= existing.effectivePriority) {
            return null // signal that new should replace existing (caller will update)
        }
        // Otherwise prefer higher confidence (priority) and more recent
        val newScore = newImportance * 0.6 + (if (newContent.length > existing.content.length) 0.2 else 0.0)
        val existingScore = existing.effectivePriority * 0.6 + 0.1 // existing gets slight recency if already stored
        return if (newScore > existingScore) null else existing
    }
}
