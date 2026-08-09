package io.androllm.core.voice.tts

/**
 * Text normalization + OOV handling for the offline VITS TTS model.
 *
 * The sherpa-onnx VITS voice ships with a plain-text lexicon that has NO
 * digit/symbol tokens — "10 + 10 = 20" produces "OOV 10. Ignore it!"
 * warnings and an EMPTY audio buffer (silence). Every sentence is converted
 * to natural English words ("ten plus ten equals twenty") first.
 *
 * Anything that still survives as a non-lexicon word (brand names, jargon,
 * model names — "Andro", "LLM", "Kotlin", …) is normally DROPPED by
 * sherpa-onnx mid-synthesis. [spellOutOfLexiconWords] re-spells those words
 * letter by letter ("LLM" → "el el em") so no word is ever silently skipped.
 */
object EnglishTtsNormalizer {

    private val ONES = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen"
    )
    private val TENS = arrayOf(
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
    )
    private val SCALES = arrayOf("", "thousand", "million", "billion")

    /** Digits/unit suffix → spoken unit (only after a number, e.g. "10km"). */
    private val UNIT_WORDS = mapOf(
        "km/h" to "kilometers per hour",
        "km" to "kilometers",
        "cm" to "centimeters",
        "mm" to "millimeters",
        "kg" to "kilograms",
        "mg" to "milligrams",
        "g" to "grams",
        "m" to "meters",
        "mph" to "miles per hour",
        "ms" to "milliseconds",
        "s" to "seconds",
        "ghz" to "gigahertz",
        "mhz" to "megahertz",
        "hz" to "hertz",
        "gb" to "gigabytes",
        "mb" to "megabytes",
        "kb" to "kilobytes",
        "sec" to "seconds",
        "min" to "minutes",
        "hrs" to "hours",
        "hr" to "hour",
        "oz" to "ounces",
        "lb" to "pounds",
        "in" to "inches",
        "ft" to "feet"
    )

    /** Word-shaped tokens (incl. contractions) used by OOV spelling. */
    private val WORD = Regex("""[A-Za-z]+(?:'[A-Za-z]+)?""")

    private val TIME = Regex("""\b(\d{1,2}):(\d{2})\b\s*([apAP]\.?[mM]\.?)?""")
    private val URL = Regex("""\bhttps?://\S+|www\.\S+""")

    fun normalize(input: String): String {
        var text = input
        // URLs get one clean word so the model never trips over slashes.
        text = text.replace(URL, " link ")
        // Time of day: "3:30 pm" → "three thirty p m".
        text = TIME.replace(text) { m ->
            val hours = intToWords(m.groupValues[1].toLongOrNull() ?: return@replace m.value)
            val min = m.groupValues[2]
            val minutes = if (min == "00") "" else {
                if (min.startsWith("0") && min.length == 2) {
                    "oh " + ONES[min[1] - '0']
                } else intToWords(min.toLong())
            }
            val period = when (m.groupValues[3].lowercase().replace(".", "")) {
                "am" -> " a m"
                "pm" -> " p m"
                else -> ""
            }
            val sb = StringBuilder(hours)
            if (minutes.isNotEmpty()) sb.append(' ').append(minutes)
            sb.append(period)
            sb.toString()
        }
        // Units glued to numbers: "10km" → "10 kilometers".
        text = text.replace(Regex("""\b(\d+(?:\.\d+)?)\s*(km/h|km|cm|mm|kg|mg|mph|ghz|mhz|hz|gb|mb|kb|ms|sec|min|hrs|hr|dec|lb|in|ft|oz)(?![A-Za-z])""", RegexOption.IGNORE_CASE)) { m ->
            val word = m.groupValues[2].lowercase()
            val expansion = UNIT_WORDS[word]
            if (expansion == null) m.value else "${m.groupValues[1]} $expansion"
        }
        // Date-ish/numeric hyphens: "2024-05-12" → "… to …" (never "minus").
        text = text.replace(Regex("(?<=\\d)-(?=\\d)"), " to ")
        // Math operators → words (order matters: "x" only between digits).
        text = text.replace(Regex("([0-9])[xX]([0-9])"), "$1 times $2")
        text = text.replace("+", " plus ")
        text = text.replace("=", " equals ")
        text = text.replace("×", " times ")
        text = text.replace("−", " minus ")
        text = text.replace("~", " approximately ")
        text = text.replace("≥", " greater than or equal to ")
        text = text.replace("≤", " less than or equal to ")
        text = text.replace(">", " greater than ")
        text = text.replace("<", " less than ")
        text = text.replace("%", " percent ")
        text = text.replace(" - ", " minus ")
        text = text.replace("/", " divided by ")
        // Degrees + units.
        text = text.replace("°C", " degrees celsius ")
        text = text.replace("°F", " degrees fahrenheit ")
        text = text.replace("°", " degrees ")
        // Integer + decimal literals (with optional thousands separators).
        text = text.replace(
            Regex("""\d+(?:,\d{3})*(?:\.\d+)?""")
        ) { m -> numberToWords(m.value) }
        // Dollar amounts (left over after numbers became words).
        text = text.replace("$", " dollars ")
        // Remaining symbols that the lexicon/tokens never contain.
        text = text.replace("&", " and ")
        text = text.replace("@", " at ")
        text = text.replace("#", " hash ")
        text = text.replace("*", " ")
        text = text.replace("_", " ")
        text = text.replace("`", " ")
        text = text.replace("|", " ")
        text = text.replace("^", " to the power of ")
        // Collapse stray whitespace and trailing sentence-enders.
        return text.trim()
            .replace(Regex("\\s{2,}"), " ")
            .trimEnd('.', ' ', '\t', '\n')
    }

    /**
     * Replaces words outside the VITS lexicon with their letter-by-letter
     * spelling (all single letters exist in the lexicon) so unknown words are
     * spoken aloud instead of silently dropped. [lexicon] = lowercase words.
     *
     * Hyphenated compounds ("state-of-the-art") and trailing-plural inflections
     * are decomposed first so common words are never needlessly spelled out.
     */
    fun spellOutOfLexicon(text: String, lexicon: Set<String>): String {
        val sb = StringBuilder(text.length)
        var lastIndex = 0
        for (m in WORD.findAll(text)) {
            sb.append(text, lastIndex, m.range.first)
            val w = m.value
            val expansion = coveredExpansion(w, lexicon)
            if (expansion != null) {
                sb.append(expansion)
            } else {
                for (i in w.indices) {
                    if (i > 0) sb.append(' ')
                    sb.append(w[i])
                }
            }
            lastIndex = m.range.last + 1
        }
        sb.append(text, lastIndex, text.length)
        return sb.toString()
    }

    private fun coveredExpansion(w: String, lexicon: Set<String>): String? {
        val word = w.lowercase()
        if (lexicon.contains(word)) return w
        // Hyphenated compound: speak the parts separately.
        if ('-' in word) {
            val parts = word.split('-')
            if (parts.all { it.isNotEmpty() && lexicon.contains(it) }) {
                return parts.joinToString(" ")
            }
        }
        // Inflections our lexicon lacks: "llamas" → "llama s" instead of "l l l a m a s".
        if (word.endsWith("'s") && lexicon.contains(word.removeSuffix("'s"))) {
            return w + " s"
        }
        if (word.endsWith("s") && word.length > 2 &&
            lexicon.contains(word.removeSuffix("s"))
        ) {
            return w + " s"
        }
        return null
    }

    private fun numberToWords(raw: String): String {
        val negative = raw.startsWith("-")
        val body = raw.removePrefix("-")
        val decimalPart = body.substringAfter('.', missingDelimiterValue = "")
        val intPart = body.substringBefore('.')
        val intWords = intToWords(intPart.replace(",", "").toLongOrNull() ?: return raw)
        val sb = StringBuilder()
        if (negative) sb.append("minus ")
        sb.append(intWords)
        if (decimalPart.isNotEmpty() && decimalPart.all { it.isDigit() }) {
            sb.append(" point")
            for (ch in decimalPart) sb.append(' ').append(ONES[ch - '0'])
        }
        return sb.toString()
    }

    private fun intToWords(n: Long): String {
        if (n == 0L) return "zero"
        if (n < 0) return "minus " + intToWords(-n)
        if (n < 20) return ONES[n.toInt()]
        if (n < 100) {
            val tens = n / 10
            val ones = n % 10
            return TENS[tens.toInt()] + if (ones > 0) " " + ONES[ones.toInt()] else ""
        }
        if (n < 1000) {
            val hundreds = n / 100
            val rest = n % 100
            return ONES[hundreds.toInt()] + " hundred" + if (rest > 0) " " + intToWords(rest) else ""
        }
        // Split into 3-digit groups (thousands, millions, billions).
        val parts = mutableListOf<Long>()
        var remaining = n
        while (remaining > 0) {
            parts.add(remaining % 1000)
            remaining /= 1000
        }
        val sb = StringBuilder()
        for (i in parts.indices.reversed()) {
            val group = parts[i]
            if (group == 0L) continue
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(intToWords(group))
            val scale = SCALES[i]
            if (scale.isNotEmpty()) sb.append(' ').append(scale)
        }
        return sb.toString()
    }
}