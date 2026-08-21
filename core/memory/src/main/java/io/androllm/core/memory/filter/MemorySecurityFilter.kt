package io.androllm.core.memory.filter

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Validates every memory entry before saving — never store secrets, injection, hallucinations, or low-value noise.
 * Runs locally on-device, no network, provider-agnostic.
 */
@Singleton
class MemorySecurityFilter @Inject constructor() {

    // High-confidence secret patterns — never store
    private val secretPatterns = listOf(
        Regex("""(?i)(api[_-]?key|apikey)\s*[:=]\s*['"]?[A-Za-z0-9_\-]{16,}['"]?"""),
        Regex("""(?i)(secret|password|passwd|pwd)\s*(?:[:=]|is)\s*['"]?.{4,}['"]?"""),
        Regex("""(?i)bearer\s+[A-Za-z0-9\-\._~\+\/]{20,}={0,2}"""),
        Regex("""\bsk-[A-Za-z0-9]{20,}\b"""), // OpenAI
        Regex("""\bghp_[A-Za-z0-9]{20,}\b"""), // GitHub
        Regex("""\bgho_[A-Za-z0-9]{20,}\b"""),
        Regex("""\b[A-Za-z0-9_\-]{24,}\.[A-Za-z0-9_\-]{6,}\.[A-Za-z0-9_\-]{20,}\b"""), // JWT
        Regex("""(?i)token\s*[:=]\s*['"]?[A-Za-z0-9_\-]{12,}['"]?"""),
        Regex("""-----BEGIN (?:RSA )?PRIVATE KEY-----"""),
        Regex("""\b[A-Za-z0-9]{32,}\b""") // generic long token — used with caution, only if context has key/secret
    )

    private val piiPatterns = listOf(
        Regex("""\b\d{3}-\d{2}-\d{4}\b"""), // SSN
        Regex("""\b(?:\d[ -]*?){13,16}\b""") // credit card candidate
    )

    private val injectionPatterns = listOf(
        Regex("""(?i)ignore\s+previous\s+instructions"""),
        Regex("""(?i)disregard\s+(?:all\s+)?previous"""),
        Regex("""(?i)pretend\s+you\s+are"""),
        Regex("""(?i)you\s+are\s+now\s+"""),
        Regex("""(?i)system\s*:\s*"""),
        Regex("""(?i)developer\s*:\s*"""),
        Regex("""(?i)do\s+not\s+follow\s+system"""),
        Regex("""(?i)override\s+system\s+rules"""),
        Regex("""(?i)jailbreak"""),
        Regex("""(?i)prompt\s+injection"""),
        Regex("""(?i)always\s+act\s+as"""),
        Regex("""(?i)never\s+follow\s+system"""),
        Regex("""<\|im_start\|>"""),
        Regex("""<tool_call""")
    )

    private val systemOverridePatterns = listOf(
        Regex("""(?i)always\s+respond\s+as"""),
        Regex("""(?i)never\s+mention\s+memory"""),
        Regex("""(?i)reveal\s+system\s+prompt""")
    )

    fun containsSecrets(content: String): Boolean {
        secretPatterns.forEach { pattern ->
            if (pattern.containsMatchIn(content)) {
                // Generic 32+ char token only counts if nearby secret keyword
                if (pattern.pattern == """\b[A-Za-z0-9]{32,}\b""") {
                    if (content.contains(Regex("""(?i)(key|secret|token|password)"""))) return true
                    else return@forEach
                } else {
                    return true
                }
            }
        }
        return false
    }

    fun containsPromptInjection(content: String): Boolean {
        return injectionPatterns.any { it.containsMatchIn(content) } ||
            systemOverridePatterns.any { it.containsMatchIn(content) }
    }

    fun isLowValue(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.length < 12) return true
        val lower = trimmed.lowercase()
        val lowValuePhrases = listOf(
            "hello", "hi there", "thanks", "thank you", "ok", "okay", "sure", "got it",
            "hello!", "hi!", "thanks!", "ok thanks", "cool", "nice", "great"
        )
        if (lowValuePhrases.any { it == lower }) return true
        // debugging noise
        if (lower.contains("debug") && lower.length < 40) return true
        if (lower.contains("console.log") || lower.contains("stacktrace") || lower.contains("exception")) return true
        // one-time casual
        if (lower.matches(Regex("""^(lol|haha|hehe|omg|wow|yes|no|maybe)\b.*"""))) return true
        return false
    }

    fun isGroundedInExchange(content: String, exchange: io.androllm.core.memory.model.MemoryExchange?): Boolean {
        if (exchange == null) return true // no exchange to compare, allow
        // Manual saves via UI (saveMemory) use empty exchange — allow without grounding check
        if (exchange.userMessage.isBlank() && exchange.assistantResponse.isBlank() && exchange.recentMessages.isEmpty()) {
            return true
        }
        // Require at least 2 significant words overlap with user or assistant
        val memoryWords = content.lowercase().split(Regex("""\W+""")).filter { it.length > 3 }.toSet()
        if (memoryWords.size < 2) return true // short memories are okay if already validated length
        val exchangeText = (exchange.userMessage + " " + exchange.assistantResponse).lowercase()
        // Also consider recentMessages for grounding (for long conversations)
        val recentText = exchange.recentMessages.joinToString(" ") { it.second }.lowercase()
        val fullExchangeText = "$exchangeText $recentText"
        val overlap = memoryWords.count { it in fullExchangeText }
        // If less than 30% of significant words appear in exchange, likely hallucinated
        return overlap >= (memoryWords.size * 0.3).coerceAtLeast(1.0)
    }

    /**
     * Validates a single extracted memory before persistence.
     * Returns null if safe, error message if rejected (never store).
     */
    fun validate(content: String, exchange: io.androllm.core.memory.model.MemoryExchange? = null): String? {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return "empty"
        if (trimmed.length > 800) return "too long"
        if (containsSecrets(trimmed)) return "contains secrets/credentials"
        if (containsPromptInjection(trimmed)) return "contains prompt injection"
        if (isLowValue(trimmed)) return "low-value casual content"
        if (!isGroundedInExchange(trimmed, exchange)) return "not grounded in exchange (hallucination)"
        // Sensitive PII: only reject high-confidence, not user preferences like "I live in Delhi" (that's identity, allowed)
        // We do not reject general location/identity, only SSN/credit card strict patterns
        if (piiPatterns.any { it.containsMatchIn(trimmed) }) return "contains sensitive PII"
        return null // safe
    }

    fun isSafe(content: String, exchange: io.androllm.core.memory.model.MemoryExchange? = null): Boolean {
        return validate(content, exchange) == null
    }
}
