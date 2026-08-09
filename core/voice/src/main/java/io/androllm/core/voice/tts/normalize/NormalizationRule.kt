package io.androllm.core.voice.tts.normalize

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * One user-definable normalization rule.
 *
 * Rules apply in [priority] order (lower first); [enabled] can be flipped
 * at runtime without touching code. This is the extension point for custom
 * normalizations (new symbols, formulas, domain words, …).
 */
data class NormalizationRule(
    val name: String,
    val pattern: Pattern,
    val replacement: String,
    val priority: Int = 100,
    val enabled: Boolean = true
) {
    companion object {
        /** Compile-safe factory — a bad regex yields null, not a crash. */
        fun of(
            name: String,
            regex: String,
            replacement: String,
            priority: Int = 100,
            enabled: Boolean = true
        ): NormalizationRule? = try {
            NormalizationRule(name, Pattern.compile(regex), replacement, priority, enabled)
        } catch (e: PatternSyntaxException) {
            null
        }

        /** Default roll of extra rules layered on top of the stage processors. */
        fun builtIns(): List<NormalizationRule> = listOfNotNull(
            of("arrow", """->|=>|&#8594;""", "to ", 3),
            of("em dash", "\u2014", ", ", 10),
            of("en dash", "\u2013", "- ", 10),
            of("bullet", "\u2022", " ", 10),
            of("ellipsis char", "\u2026", " ", 10),
            of("nbsp", "\u00A0", " ", 5),
            of("section", "§", " section ", 10),
            of("copyright", "©", " copyright ", 10),
            of("registered", "®", " registered ", 10),
            of("tm", "™", " trademark ", 10)
        )
    }
}

/**
 * Ordered, grep-able regex rules applied as one pipeline stage — the
 * "custom rule engine": rules are data, not code, so future normalizers
 * slot in without touching stage implementations.
 */
class CustomRuleProcessor(
    rules: List<NormalizationRule>
) : TextProcessor {

    private val enabledRules: List<NormalizationRule> = rules
        .filter { it.enabled }
        .sortedBy { it.priority }

    override fun process(input: String): String {
        var text = input
        for (rule in enabledRules) {
            text = rule.pattern.matcher(text).replaceAll(rule.replacement)
        }
        return text
    }
}