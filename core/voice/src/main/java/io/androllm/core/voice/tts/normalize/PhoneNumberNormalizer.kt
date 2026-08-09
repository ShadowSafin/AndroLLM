package io.androllm.core.voice.tts.normalize

import java.util.regex.Pattern

/**
 * Phone numbers → digit-by-digit audio.
 *
 *   * "+91 9876543210" → "plus nine nine eight seven six five four three two one zero"
 *   * "(555) 123-4567" → "five five five one two three four five six seven"
 *
 * Only matches a plausible phone-length run (10–15 digits) — a shorter
 * window would swallow ISO dates ("2024-05-12", which the date stage needs
 * to see first) and 4-digit year fragments.
 */
class PhoneNumberNormalizer : TextProcessor {

    override fun process(input: String): String = replaceAll(input)

    companion object {
        // Country-code optional, then 10-15 digits split by spaces/dashes/dots/parens.
        private val PHONE = Pattern.compile(
            """(?<![0-9])(?:\+?\d[\s\-()]{0,2}){0,3}(?:\d[\s\-()\.]{0,2}){10,15}(?![0-9])"""
        )
    }

    private fun replaceAll(input: String): String =
        PHONE.matcher(input).replaceAll { m -> phoneToWords(m.group()) }

    private fun phoneToWords(raw: String): String {
        val sb = StringBuilder()
        for (ch in raw) {
            when {
                ch == '+' -> sb.append("plus ")
                ch.isDigit() -> {
                    sb.append(digitToWord(ch)).append(' ')
                }
            }
        }
        return sb.toString().trimEnd()
    }

    private fun digitToWord(d: Char): String = when (d) {
        '0' -> "zero"; '1' -> "one"; '2' -> "two"; '3' -> "three"
        '4' -> "four"; '5' -> "five"; '6' -> "six"; '7' -> "seven"
        '8' -> "eight"; '9' -> "nine"
        else -> ""
    }
}