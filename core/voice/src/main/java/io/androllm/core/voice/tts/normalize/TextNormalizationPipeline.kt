package io.androllm.core.voice.tts.normalize

import java.util.regex.Pattern

/**
 * Named pipeline stage: id (for debug traces), the [TextProcessor], and a
 * gate deciding whether it runs for a given [NormalizationSettings] snapshot.
 */
class NamedStage(
    val id: String,
    val processor: TextProcessor,
    val enabled: (NormalizationSettings) -> Boolean
)

/** Result of one pipeline pass; [traces] populated when debug mode is on. */
data class NormalizationResult(
    val text: String,
    val traces: List<Trace> = emptyList()
) {
    data class Trace(val stageId: String, val input: String, val output: String)
}

/**
 * Sequential pipeline — LLM output → stages → TTS-ready text.
 *
 * Stage order is load-bearing:
 *
 *   1. URLs & Emails    (protect "github.com" before "." becomes "point")
 *   2. Phone numbers    (long digit runs before plain-number spelling)
 *   3. Currency         ("€19.99" → "nineteen euros and ninety nine cents")
 *   4. Dates & Times    ("14:30" consumed before math/numbers)
 *   5. Units            ("10km" splits before number spelling, "120 MHz")
 *   6. Math             ("2+2", "16:9", "≈", "≥")
 *   7. Numbers          (integers, decimals, %, ordinals, versions, IP, roman)
 *   8. Abbreviations    ("GPU" → "g p u"; "GHz" → "gigahertz"; "U.S." → "u s")
 *   9. Emoji & Symbols  (descriptions + punctuation cleanup, dots → "dot")
 *  10. Custom rules     (the rule engine — [NormalizationRule.builtIns])
 *
 * Every stage independently testable; toggles from persisted settings.
 */
class TextNormalizationPipeline(
    private val stages: List<NamedStage>,
    private val settingsProvider: () -> NormalizationSettings
) {

    fun process(input: String): NormalizationResult {
        val settings = settingsProvider()
        if (input.isBlank() || !settings.enabled) return NormalizationResult(input)
        val traces = if (settings.debug) ArrayList<NormalizationResult.Trace>(stages.size) else null
        var current = input
        for (stage in stages) {
            if (!stage.enabled(settings)) continue
            val before = current
            val after = stage.processor.process(before)
            if (traces != null) traces.add(NormalizationResult.Trace(stage.id, before, after))
            current = after
        }
        return NormalizationResult(finish(current), traces ?: emptyList())
    }

    private fun finish(text: String): String {
        var t = text.trim().replace('\u00A0', ' ').replace(Regex("""\s{2,}"""), " ")
        t = TRIM_PUNCT.matcher(t).replaceAll("")
        t = STRAY_QUOTES.matcher(t).replaceAll("")
        return t.trim()
    }

    companion object {
        private val TRIM_PUNCT = Pattern.compile("""[.,!?;:]+$""")
        private val STRAY_QUOTES = Pattern.compile("""["']""")

        /** The standard stage bank, wired to the persisted settings snapshot. */
        fun defaultStages(): List<NamedStage> = listOf(
            NamedStage("URLs & Emails", UrlEmailNormalizer(), { it.urlsEmails }),
            NamedStage("Phone numbers", PhoneNumberNormalizer(), { it.phones }),
            NamedStage("Currency", CurrencyNormalizer(), { it.currency }),
            NamedStage("Dates & Times", DateTimeNormalizer(), { it.dates }),
            NamedStage("Units", UnitNormalizer(), { it.units }),
            NamedStage("Math", MathExpressionNormalizer(), { it.math }),
            NamedStage("Numbers", NumberNormalizer(), { it.numbers }),
            NamedStage("Abbreviations", AbbreviationNormalizer(), { it.abbreviations }),
            NamedStage("Emoji & Symbols", EmojiSymbolNormalizer(), { it.emoji }),
            NamedStage("Custom rules", CustomRuleProcessor(NormalizationRule.builtIns()), { true })
        )
    }
}