package io.androllm.core.voice.tts

/**
 * Text normalization for the offline VITS TTS model.
 *
 * The sherpa-onnx VITS voices shipped with the app are trained on
 * plain-text lexicons that contain NO digit or symbol tokens — feeding
 * "10 + 10 = 20" produces "OOV 10. Ignore it!" warnings and an EMPTY
 * audio buffer (silence). Every sentence must be converted to natural
 * English words ("ten plus ten equals twenty") before synthesis.
 *
 * Covers: integers (0 … billions), decimals, negative numbers, the common
 * math symbols (+ - = × x / %), dollar amounts and a handful of punctuation
 * cases. Unknown/unhandled tokens are left as-is (the model either knows
 * them or the utterance still has enough real words to speak).
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

    fun normalize(input: String): String {
        var text = input
        // Math symbols → words (order matters: "x" only when flanked by
        // digits; long symbol strings must be replaced in ONE pass so "+"
        // never survives to be replaced again).
        text = text.replace(Regex("([0-9])[xX]([0-9])"), "$1 times $2")
        text = text.replace("+", " plus ")
        text = text.replace("=", " equals ")
        text = text.replace("×", " times ")
        text = text.replace("−", " minus ")
        text = text.replace("~", " approximately ")
        text = text.replace("%", " percent ")
        text = text.replace(" - ", " minus ")
        text = text.replace("/", " divided by ")
        // Integer + decimal literals (with optional thousands separators).
        text = text.replace(
            Regex("""\d+(?:,\d{3})*(?:\.\d+)?""")
        ) { m -> numberToWords(m.value) }
        // "$" amounts (left over after numbers became words).
        text = text.replace("$", " dollars ")
        // Collapse stray whitespace and stray doubled spaces.
        return text.trim()
            .replace(Regex("\\s{2,}"), " ")
            .trimEnd('.', ' ', '\t', '\n')
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
