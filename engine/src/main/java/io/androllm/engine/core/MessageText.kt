package io.androllm.engine.core

import com.google.ai.edge.litertlm.Message

/**
 * Extracts ONLY the plain assistant text from a LiteRT-LM [Message].
 *
 * WHY THIS EXISTS: `Message.toString()` serializes the full structured
 * contents list as JSON — a message whose contents carry text parts renders
 * as `[{"type":"text","text":"Hello"}]`. The streaming callbacks used to
 * feed `partial.toString()` straight into the token stream, so whenever the
 * runtime delivered a structured (multi-content) partial, the raw JSON array
 * leaked into the chat UI verbatim. The only user-facing content of an
 * assistant message is its `Content.Text` parts; tool responses, image/audio
 * parts and channel metadata must never be rendered.
 *
 * [cleanSerialized] is the defensive second layer: if a fragment still
 * arrives PRE-SERIALIZED as a JSON contents array (older runtime builds that
 * stringify before invoking the callback), the JSON shape is detected and
 * flattened to the concatenated `text` field values. Prose is never touched —
 * the pattern requires the exact `{"type":"text","text":"..."}` element shape.
 */
object MessageText {

    /** One serialized text-content element: `{"type":"text","text":"…"}`. */
    private val TEXT_ELEMENT_REGEX = Regex(
        """\{\s*"type"\s*:\s*"text"\s*,\s*"text"\s*:\s*"((?:[^"\\]|\\.)*)"\s*\}"""
    )

    /**
     * A whole serialized contents payload: `[ …elements… ]` (optionally
     * wrapped in whitespace). Only matched when it contains at least one
     * complete text element, so ordinary prose starting with "[" survives.
     */
    private val SERIALIZED_CONTENTS_REGEX = Regex(
        """^\s*\[(?:\s*${TEXT_ELEMENT_REGEX.pattern}\s*,?\s*)+\]\s*$""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )

    /**
     * Plain user-visible text of [message]: the concatenation of its
     * `Content.Text` parts. Tool responses, images, audio and binary parts
     * are deliberately dropped — they are internal data structures, never
     * chat text.
     */
    fun from(message: Message): String = buildString {
        runCatching {
            for (content in message.contents.contents) {
                if (content is com.google.ai.edge.litertlm.Content.Text) {
                    append(content.text)
                }
            }
        }
    }

    /**
     * Flattens a fragment that arrived pre-serialized as a JSON contents
     * array (`[{"type":"text","text":"Hi"},{"type":"text","text":"!"}]` →
     * `Hi!`). Returns [fragment] unchanged when it is not that shape.
     */
    fun cleanSerialized(fragment: String): String {
        if (!fragment.startsWith("[")) return fragment
        val match = SERIALIZED_CONTENTS_REGEX.find(fragment) ?: return fragment
        return buildString {
            for (element in TEXT_ELEMENT_REGEX.findAll(match.value)) {
                append(unescapeJson(element.groupValues[1]))
            }
        }
    }

    /** Boundary entry point: extract from a Message AND flatten any residue. */
    fun extract(message: Message): String =
        cleanSerialized(from(message))

    /** Minimal JSON string unescape for the fields extracted above. */
    private fun unescapeJson(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        val hex = s.substring(i + 2, (i + 6).coerceAtMost(s.length))
                        val code = hex.toIntOrNull(16)
                        if (code != null && hex.length == 4) {
                            sb.append(code.toChar())
                            i += 4
                        } else {
                            sb.append(n)
                        }
                    }
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
