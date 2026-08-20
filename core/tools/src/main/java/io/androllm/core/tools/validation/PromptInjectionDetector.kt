package io.androllm.core.tools.validation

import timber.log.Timber

/**
 * Detects and neutralizes prompt injection attempts that try to:
 * - invent tools
 * - bypass validation
 * - modify available tool list
 * - inject hidden instructions inside retrieved documents
 *
 * Never allows a prompt to modify the available tool list.
 */
object PromptInjectionDetector {

    private val INJECTION_PATTERNS = listOf(
        // Attempts to invent tools
        Regex("""\b(create|register|add|invent|make)\s+(a\s+)?new\s+tool\b""", RegexOption.IGNORE_CASE),
        Regex("""\bnew tool\s*:""", RegexOption.IGNORE_CASE),
        Regex("""\btool\s+name\s*:\s*["']?\w+["']?""", RegexOption.IGNORE_CASE),
        // Bypass validation
        Regex("""\bbypass.*validation\b""", RegexOption.IGNORE_CASE),
        Regex("""\bskip.*validation\b""", RegexOption.IGNORE_CASE),
        Regex("""\bignore.*validation\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdisable.*validation\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdo not validate\b""", RegexOption.IGNORE_CASE),
        Regex("""\bwithout validation\b""", RegexOption.IGNORE_CASE),
        // Modify tool list
        Regex("""\bmodify.*tool\s*list\b""", RegexOption.IGNORE_CASE),
        Regex("""\badd.*to.*registry\b""", RegexOption.IGNORE_CASE),
        Regex("""\bavailable tools.*are\b""", RegexOption.IGNORE_CASE),
        // Classic prompt injection
        Regex("""\bignore\s+previous\s+instructions\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdisregard\s+previous\b""", RegexOption.IGNORE_CASE),
        Regex("""\bpretend\s+you\s+are\b""", RegexOption.IGNORE_CASE),
        Regex("""\byou\s+are\s+now\b""", RegexOption.IGNORE_CASE),
        Regex("""\bsystem\s*:\s*""", RegexOption.IGNORE_CASE),
        Regex("""\bdeveloper\s*:\s*""", RegexOption.IGNORE_CASE),
        Regex("""\[SYSTEM\]""", RegexOption.IGNORE_CASE),
        Regex("""\[INST\]""", RegexOption.IGNORE_CASE),
        // Tool syntax injection in documents
        Regex("""<\s*tool_call\b""", RegexOption.IGNORE_CASE),
        Regex("""\{\s*"name"\s*:\s*".*?"\s*,\s*"arguments"\s*:""", RegexOption.IGNORE_CASE)
    )

    private val TOOL_INVENTION_KEYWORDS = listOf(
        "create_tool", "new_tool", "register_tool", "add_tool", "invent_tool"
    )

    /**
     * Returns true if [text] appears to be a prompt injection attempt.
     */
    fun isInjectionAttempt(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        // Direct keyword check
        if (TOOL_INVENTION_KEYWORDS.any { it in lower }) return true
        // Pattern matching
        return INJECTION_PATTERNS.any { it.containsMatchIn(text) }
    }

    /**
     * Checks if a tool name looks like an injection (hallucinated tool).
     * Hallucinated names often contain spaces, uppercase, or invented prefixes.
     */
    fun isHallucinatedToolName(name: String, knownTools: Set<String>): Boolean {
        if (name.isBlank()) return true
        // Unknown tool is hallucinated — model invented a tool not in registry
        if (name !in knownTools) return true
        // Also flag invalid naming pattern as hallucinated even if somehow in known
        if (!name.matches(Regex("^[a-z][a-z0-9_]*$"))) return true
        return false
    }

    /**
     * Sanitizes retrieved document content by stripping hidden instructions.
     * Removes tool-like syntax and injection patterns that could be executed
     * if passed to the model.
     */
    fun sanitizeRetrievedDocument(content: String): String {
        if (content.isBlank()) return content
        var sanitized = content
        // Remove tool call blocks
        sanitized = sanitized.replace(Regex("""<\s*tool_call\b[^>]*>.*?<\s*/\s*tool_call\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "[removed tool call]")
        sanitized = sanitized.replace(Regex("""\{\s*"name"\s*:\s*"[^"]*"\s*,\s*"arguments"\s*:\s*\{[^}]*\}\s*\}"""), "[removed tool syntax]")
        // Remove system/developer injections
        sanitized = sanitized.replace(Regex("""\b(ignore previous instructions|disregard.*|bypass validation)[^\n]*""", RegexOption.IGNORE_CASE), "[removed injection]")
        val originalLength = content.length
        if (sanitized.length != originalLength) {
            Timber.w("PromptInjectionDetector: sanitized retrieved document (${originalLength - sanitized.length} chars removed)")
        }
        return sanitized
    }

    /**
     * Validates that a user prompt is not attempting to modify tool list.
     * Returns null if safe, error message if injection detected.
     */
    fun validateUserPrompt(prompt: String): String? {
        if (isInjectionAttempt(prompt)) {
            Timber.w("PromptInjectionDetector: injection attempt detected in prompt: ${prompt.take(100)}")
            return "Prompt contains potential injection — ignoring tool-related instructions"
        }
        return null
    }

    /**
     * Ensures tool list cannot be modified via prompt — always returns the
     * registry's list, never a prompt-derived list.
     */
    fun filterToolListFromPrompt(promptTools: List<String>, registryTools: Set<String>): List<String> {
        // Only allow tools that are actually in registry
        return promptTools.filter { it in registryTools }
    }
}
