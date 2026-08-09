package io.androllm.core.voice.tts.normalize

import java.util.regex.Pattern

/**
 * Numbers → words, converted on-device with the spellout the VITS lexicon
 * already understands (see [SpeechNumbers]; ICU4J's spellout is avoided
 * because its hyphenated tens are OOV for the lexicon).
 *
 * Covers: integers, decimals, negatives, percentages, ordinals, simple
 * fractions, version numbers, IP addresses, superscripts, Roman numerals.
 */
class NumberNormalizer : TextProcessor {

    override fun process(input: String): String = replaceAll(input)

    private companion object {
        private val NUMBER = Pattern.compile("""(?<![\w.])-?\d+(?:,\d{3})*(?:\.\d+)?(?![\w.])""")
        private val PERCENT = Pattern.compile("""(?<![\w.])-?\d+(?:,\d{3})*(?:\.\d+)?\s*%(?![\w.])""")
        private val VERSION = Pattern.compile("""(?<![\w.])v\d+(?:\.\d+)+""", Pattern.CASE_INSENSITIVE)
        private val IP = Pattern.compile("""(?<![\w.])-?\d{1,3}(?:\.\d{1,3}){3}(?![\w.])""")
        private val FRACTION = Pattern.compile("""(?<![\w.])-?\d{1,2}/\d{1,2}(?![\w/])""")
        private val ORDINAL = Pattern.compile("""(?<![\w.])-?\d+(?:st|nd|rd|th)(?![\w])""")
        private val ROMAN = Pattern.compile("""(?<![\w])[IVXLCDM]{2,7}(?![\w])""")
        private val SUPERSCRIPT_WORD = Pattern.compile("""[²³¹⁴-⁹]""")
    }

    internal fun replaceAll(input: String): String {
        var text = input

        // Superscripts: "x²" → "x squared"; "10³" → "ten cubed"; "x⁴" → "x to the fourth".
        text = SUPERSCRIPT_WORD.matcher(text).replaceAll { m ->
            when (m.group()) {
                "²" -> " squared"
                "³" -> " cubed"
                "⁴", "⁵", "⁶", "⁷", "⁸", "⁹" -> " to the " + SpeechNumbers.ordinal((m.group()[0] - '⁰').toLong())
                "⁰", "¹" -> ""
                else -> ""
            }
        }

        // Percentages: "94%" → "ninety-four percent" (before plain numbers).
        text = PERCENT.matcher(text).replaceAll { m ->
            percentToWords(m.group())
        }

        // Versions: "v2.4.1" → "version two point four point one".
        text = VERSION.matcher(text).replaceAll { m -> versionToWords(m.group()) }

        // IP addresses: "192.168.1.1" → "one nine two dot one six eight dot one dot one".
        text = IP.matcher(text).replaceAll { m -> ipToWords(m.group()) }

        // Fractions: "1/2" → "one half"; "3/4" → "three quarters".
        text = FRACTION.matcher(text).replaceAll { m -> fractionToWords(m.group()) ?: m.group() }

        // Ordinals: "21st" → "twenty first".
        text = ORDINAL.matcher(text).replaceAll { m -> ordinalToWords(m.group()) }

        // General integers and decimals.
        text = NUMBER.matcher(text).replaceAll { m -> numberToWords(m.group()) }

        // Roman numerals: standalone "IV" → "four"; "X" skipped (too ambiguous to speak alone).
        text = ROMAN.matcher(text).replaceAll { m ->
            val v = romanValue(m.group())
            if (v != null && m.group().length >= 2) SpeechNumbers.int(v.toLong()) else m.group()
        }
        return text
    }

    internal fun numberToWords(raw: String): String {
        val negative = raw.startsWith("-")
        val body = raw.trim().removePrefix("-")
        val decimalPart = body.substringAfter('.', missingDelimiterValue = "!")
        val intPart = body.substringBefore('.')
        val intValue = try {
            intPart.replace(",", "").toLong()
        } catch (t: NumberFormatException) {
            return raw
        }
        val sb = StringBuilder()
        if (negative) sb.append("minus ")
        sb.append(SpeechNumbers.int(intValue))
        if (decimalPart != "!" && decimalPart.isNotEmpty() && decimalPart.all { it.isDigit() }) {
            sb.append(" point")
            for (digit in decimalPart) sb.append(' ').append(digitToWord(digit))
        }
        return sb.toString()
    }

    private fun percentToWords(token: String): String {
        val body = token.removeSuffix("%").trim()
        return numberToWords(body) + " percent"
    }

    private fun fractionToWords(raw: String): String? {
        val negative = raw.startsWith("-")
        val parts = raw.removePrefix("-").split("/")
        if (parts.size != 2) return null
        val numerator = parts[0].removeSuffix(".").toLongOrNull() ?: return null
        val denominator = parts[1].removeSuffix(".").toLongOrNull() ?: return null
        if (numerator !in 1..12 || denominator !in 2..12) return null
        val denWords = when (denominator) {
            2L -> if (numerator == 1L) "half" else "halves"
            3L -> "thirds"
            4L -> "quarters"
            8L -> "eighths"
            10L -> "tenths"
            12L -> "twelfths"
            else -> SpeechNumbers.ordinal(denominator) + "s"
        }
        return (if (negative) "minus " else "") + SpeechNumbers.int(numerator) + " " + denWords
    }

    internal fun ordinalToWords(raw: String): String {
        val body = raw
            .removeSuffix("st").removeSuffix("nd").removeSuffix("rd").removeSuffix("th")
        val n = body.toLongOrNull() ?: return raw
        return SpeechNumbers.ordinal(n)
    }

    private fun versionToWords(raw: String): String {
        val digits = raw.trimStart('v', 'V')
        return "version " + digitAudio(digits, separator = " point ")
    }

    private fun ipToWords(raw: String): String =
        digitAudio(raw.removePrefix("-"), separator = " dot ")

    private fun digitAudio(raw: String, separator: String): String =
        raw.split('.').joinToString(separator) { group ->
            if (group.isEmpty()) "" else group.map(::digitToWord).joinToString(" ")
        }

    private fun digitToWord(d: Char): String = when (d) {
        '0' -> "zero"; '1' -> "one"; '2' -> "two"; '3' -> "three"
        '4' -> "four"; '5' -> "five"; '6' -> "six"; '7' -> "seven"
        '8' -> "eight"; '9' -> "nine"
        else -> ""
    }

    /** "IV" → 4; "MCMXCIV" → 1994. Returns null when not a valid numeral. */
    private fun romanValue(raw: String): Int? {
        var total = 0
        var prev = 0
        for (ch in raw.reversed()) {
            val v = when (ch) {
                'I' -> 1; 'V' -> 5; 'X' -> 10; 'L' -> 50
                'C' -> 100; 'D' -> 500; 'M' -> 1000
                else -> return null
            }
            if (v < prev) total -= v else total += v
            prev = v
        }
        if (total !in 1..4999) return null
        // No more than three repetitions of I/X/C/M and single V/L/D.
        if (Regex("""[IVXLCDM]{1,9}""").matches(raw) &&
            !Regex("""(I{4,}|X{4,}|C{4,}|M{4,}|V{2,}|L{2,}|D{2,})""").containsMatchIn(raw)
        ) return total
        return null
    }
}