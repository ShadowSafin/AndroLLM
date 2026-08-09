package io.androllm.core.voice.tts.normalize

/**
 * Pure number→words spellout shared by [NumberNormalizer] and
 * [CurrencyNormalizer]. Deterministic British/American English output that is
 * verified against the VITS lexicon ("point", "twenty", "thousand", ...).
 *
 * Re-implemented instead of ICU4J's spellout (`RuleBasedNumberFormat`) because
 * (a) Android's bundled ICU data varies by device, (b) ICU emits hyphenated
 * tens ("twenty-three") that the VITS lexicon does not contain — dropping the
 * word unless re-joined — and (c) we only ever speak English.
 */
object SpeechNumbers {

    private val ONES = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen"
    )
    private val TENS = arrayOf(
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
    )
    private val SCALES = arrayOf("", "thousand", "million", "billion", "trillion")

    /** 123 → "one hundred twenty three" (no hyphens — they are not in the lexicon). */
    fun int(n: Long): String {
        if (n == 0L) return "zero"
        if (n < 0) return "minus " + int(-n)
        if (n < 20) return ONES[n.toInt()]
        if (n < 100) {
            val tens = n / 10
            val ones = n % 10
            return TENS[tens.toInt()] + if (ones > 0) " " + ONES[ones.toInt()] else ""
        }
        if (n < 1000) {
            val hundreds = n / 100
            val rest = n % 100
            return ONES[hundreds.toInt()] + " hundred" + if (rest > 0) " " + int(rest) else ""
        }
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
            sb.append(int(group))
            val scale = SCALES[i]
            if (scale.isNotEmpty()) sb.append(' ').append(scale)
        }
        return sb.toString()
    }

    /** 3.14 → "three point one four"; 0.5 → "zero point five". */
    fun decimal(intPart: Long, fractionDigits: String): String {
        val sb = StringBuilder(int(intPart))
        sb.append(" point")
        for (dig in fractionDigits) {
            sb.append(' ').append(ONES[dig - '0'])
        }
        return sb.toString()
    }

    /** 21 → "twenty-first"→ "twenty first"; 1 → "first"; 3 → "third"; 12 → "twelfth". */
    fun ordinal(n: Long): String {
        if (n <= 0) return int(n)
        if (n % 1000L == 0L) return int(n / 1000) + " thousandth"
        if (n % 1000L != 0L && n % 100L == 0L) return int(n / 100L) + " hundredth"
        val special = when (n) {
            1L -> "first"
            2L -> "second"
            3L -> "third"
            4L -> "fourth"
            5L -> "fifth"
            8L -> "eighth"
            9L -> "ninth"
            12L -> "twelfth"
            else -> null
        }
        if (special != null) return special
        if (n < 20) return int(n) + "th"
        val tens = n / 10
        val ones = n % 10
        return when (ones) {
            0L -> TENS[tens.toInt()].let { stem ->
                when (stem) {
                    "twenty" -> "twentieth"
                    "thirty" -> "thirtieth"
                    "forty" -> "fortieth"
                    "fifty" -> "fiftieth"
                    else -> stem + "tieth"
                }
            }
            else -> TENS[tens.toInt()] + " " + ordinal(ones)
        }
    }
}