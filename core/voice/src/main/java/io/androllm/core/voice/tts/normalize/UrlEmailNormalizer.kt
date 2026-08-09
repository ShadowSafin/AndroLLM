package io.androllm.core.voice.tts.normalize

import java.util.regex.Pattern

/**
 * URLs, emails and bare domain names → spoken web words.
 *
 *   * "https://github.com" → "github dot com"
 *   * "www.example.co.uk" → "example dot co uk"
 *   * "user@example.com" → "user at example dot com"
 *
 * Paths and query strings are spoken as "slash …" fragments — "slash" is
 * a real word the VITS lexicon knows. Protocols ("https://", "www.") are
 * dropped, "mailto:" shouts "email".
 *
 * Host labels must START WITH A LETTER ("github.com"), so decimals
 * ("2.0"), versions ("V2.4.1") and IP addresses fall through to the
 * number/date stages intact instead of being read as domains.
 */
class UrlEmailNormalizer : TextProcessor {

    override fun process(input: String): String = replaceAll(input)

    companion object {
        // Every DNS label begins with a letter — this also shields "2.0"
        // (decimal) and "192.168.1.1" (IP) from the URL machine.
        private val HOST = """[a-zA-Z][a-zA-Z0-9-]*(?:\.[a-zA-Z][a-zA-Z0-9-]*)+"""
        private val URL = Pattern.compile(
            """\b(?:https?://|ftp://)?(www\d{0,3}\.)?($HOST)((?:/[^\s,.;:!?()]*)*)\b"""
        )
        private val EMAIL = Pattern.compile(
            """\b([a-zA-Z][a-zA-Z0-9._%+-]*)@(?:[a-zA-Z][a-zA-Z0-9-]*\.)*[a-zA-Z][a-zA-Z0-9-]*\b"""
        )
        private val WWW_BARE = Pattern.compile("""\bwww\.([a-zA-Z][a-zA-Z0-9-]*(?:\.[a-zA-Z][a-zA-Z0-9-]*)*)\b""")
    }

    private fun replaceAll(input: String): String {
        var text = input
        text = EMAIL.matcher(text).replaceAll { m ->
            val user = m.group(1)
            val host = m.group(2)
            "$user at " + host.split('.').joinToString(" dot ")
        }
        text = WWW_BARE.matcher(text).replaceAll { m ->
            val domain = m.group(1)
            domain.split('.').joinToString(" dot ")
        }
        text = URL.matcher(text).replaceAll { m ->
            val host = m.group(2)
            val path = m.group(3) ?: ""
            val hostWords = host.split('.').joinToString(" dot ")
            if (path.isNotEmpty()) hostWords + " slash" + path.replace("/", " slash ")
            else hostWords
        }
        return text
    }
}