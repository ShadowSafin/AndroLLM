package io.androllm.core.voice.tts.normalize

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Dates & times → spoken forms.
 *
 *   * "08/09/2026" → "August ninth twenty twenty-six"
 *   * "14:30" → "two thirty pm"; "3:30 am" → "three thirty am"
 *   * "09:05" → "nine oh five"
 *   * "2026-08-09" → "August ninth twenty twenty-six"
 *
 * Date/time parsing uses official `java.text` semantics on Android (ICU)
 * and OpenJDK on the test JVM.
 */
class DateTimeNormalizer : TextProcessor {

    override fun process(input: String): String = replaceAll(input)

    companion object {
        private val MONTHS = arrayOf(
            "", "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        )
        // mm/dd/yyyy (with / . or - separators)
        private val DATE_SLASH = Pattern.compile("""\b(\d{1,2})/(\d{1,2})/(\d{4})\b""")
        // yyyy-mm-dd
        private val DATE_ISO = Pattern.compile("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")
        // "in 2026", "the year 2019" — note: bare "2024" inside "1,024"
        // globals are rarer than numeric fragments, so we only take 4-digit
        // values bounded by non-digits and not a thousands group.
        private val YEAR = Pattern.compile("""(?<![0-9A-Za-z])(19[7-9][0-9]|20[0-2][0-9])(?![0-9A-Za-z])""")
        // times: 14:30, 9:07, 3:30:05pm / 09:05 am
        private val TIME = Pattern.compile(
            """\b(\d{1,2}):(\d{2})(?::(\d{2}))?(?:\s*([ap])\.?m\.?)?\b""",
            Pattern.CASE_INSENSITIVE
        )
    }

    private fun replaceAll(input: String): String {
        var text = input
        text = DATE_ISO.matcher(text).replaceAll { m ->
            dateToWords(m.group(2), m.group(3), m.group(1)) ?: m.group()
        }
        text = DATE_SLASH.matcher(text).replaceAll { m ->
            dateToWords(m.group(1), m.group(2), m.group(3)) ?: m.group()
        }
        text = TIME.matcher(text).replaceAll { m ->
            timeToWords(m) ?: m.group()
        }
        text = YEAR.matcher(text).replaceAll { m ->
            yearToWords(m.group().toLongOrNull() ?: return@replaceAll m.group())
        }
        return text
    }

    private fun dateToWords(monthStr: String, dayStr: String, yearStr: String): String? {
        val m = monthStr.toIntOrNull() ?: return null
        val d = dayStr.toIntOrNull() ?: return null
        val y = yearStr.toLongOrNull() ?: return null
        if (m !in 1..12 || d !in 1..31) return null
        return MONTHS[m] + " " + SpeechNumbers.ordinal(d.toLong()) + " " + yearToWords(y)
    }

    /** 2026 → "twenty twenty six"; 2007 → "two thousand seven"; 1984 → "nineteen eighty four". */
    private fun yearToWords(y: Long): String = when {
        y in 2000..2009 -> "two thousand " + SpeechNumbers.int(y - 2000)
        y >= 1900 && y < 2100 -> SpeechNumbers.int(y / 100) +
            if (y % 100 == 0L) "" else " " + SpeechNumbers.int(y % 100)
        else -> SpeechNumbers.int(y)
    }

    /**
     * "14:30" → "two thirty pm"; "09:05" → "nine oh five".
     * A single-digit minute (e.g. "16:9") is a RATIO, not a time — leave it.
     */
    private fun timeToWords(m: java.util.regex.MatchResult): String? {
        val hour = m.group(1).toIntOrNull() ?: return null
        val minute = m.group(2).toIntOrNull() ?: return null
        val second = m.group(3)?.toIntOrNull()
        if (hour < 0 || hour > 23 || minute !in 0..59) return null
        val marker = m.group(4)?.lowercase()

        var h = hour
        var suffix = ""
        when (marker) {
            "a" -> { if (h == 12) h = 0; suffix = " am" }
            "p" -> { if (h != 12) h %= 12; suffix = " pm" }
            else -> if (h > 12) { h -= 12; suffix = " pm" }
        }
        if (suffix.isNotEmpty() && h == 0) h = 12
        val sb = StringBuilder(SpeechNumbers.int(h.toLong()))
        when {
            minute == 0 && second == null -> Unit
            minute < 10 -> sb.append(" oh ").append(SpeechNumbers.int(minute.toLong()))
            else -> sb.append(' ').append(SpeechNumbers.int(minute.toLong()))
        }
        if (second != null && second > 0) {
            sb.append(' ').append(SpeechNumbers.int(second.toLong())).append(" seconds")
        }
        if (suffix.isNotEmpty()) sb.append(suffix)
        return sb.toString()
    }
}