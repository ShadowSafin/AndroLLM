package io.androllm.core.voice.tts.normalize

import java.util.regex.Pattern

/**
 * Emoji + stray symbols → spoken descriptions (via [EmojiDictionary]) with
 * markdown/punctuation cleanup as a safety net for anything the VITS model
 * cannot tokenize.
 */
class EmojiSymbolNormalizer : TextProcessor {

    override fun process(input: String): String = replaceAll(input)

    companion object {
        private val MARKDOWN = Pattern.compile("""[*_`;\[\]{}]+""")
        private val PUNCT = Pattern.compile("""[()'"<>“”„]+""")
        private val TRIPLE = Pattern.compile("""\.{3,}""")
        private val MID_DOT = Pattern.compile("""(?<=[A-Za-z])\.(?=[A-Za-z])""")
        private val AT = Pattern.compile("""@""")
        private val HASH = Pattern.compile("""#""")
        private val AMP = Pattern.compile("""&""")
        private val SLASH_RUN = Pattern.compile("""/{2,}""")
        private val MID_SLASH = Pattern.compile("""(?<=[A-Za-z0-9])/(?=[A-Za-z0-9])""")
        private val SPACES = Regex("""\s{2,}""")
    }

    private fun replaceAll(input: String): String {
        val sb = StringBuilder(input.length + 32)
        var i = 0
        while (i < input.length) {
            val match = matchEmoji(input, i)
            if (match != null) {
                sb.append(' ').append(match.second).append(' ')
                i += match.first
            } else {
                sb.append(input[i])
                i++
            }
        }
        val base = MARKDOWN.matcher(sb.toString().trim()).replaceAll(" ")
        val cleaned = PUNCT.matcher(base).replaceAll(" ")
        val ellipsized = TRIPLE.matcher(cleaned).replaceAll(" dot dot dot ")
        val dotted = MID_DOT.matcher(ellipsized).replaceAll(" dot ")
        val at = AT.matcher(dotted).replaceAll(" at ")
        val hashed = HASH.matcher(at).replaceAll(" hash ")
        val amp = AMP.matcher(hashed).replaceAll(" and ")
        val slashed = SLASH_RUN.matcher(amp).replaceAll(" slash ")
        val midSlashed = MID_SLASH.matcher(slashed).replaceAll(" slash ")
        return SPACES.replace(midSlashed.trim(), " ")
    }

    /** Longest-first emoji lookup at [i]; null when it's plain text. */
    private fun matchEmoji(text: String, i: Int): Pair<Int, String>? {
        for (len in 5 downTo 1) {
            if (i + len <= text.length) {
                val words = EmojiDictionary.lookup(text.substring(i, i + len))
                if (words != null) return len to words
            }
        }
        return null
    }
}