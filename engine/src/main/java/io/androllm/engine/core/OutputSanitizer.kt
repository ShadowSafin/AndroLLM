package io.androllm.engine.core

/**
 * Central output-sanitization layer. EVERY model response — local LiteRT or
 * cloud, text chat or voice — passes through this before it can reach the UI
 * or the TTS engine.
 *
 * Guarantees:
 *  - Native tool-call blocks (`<|tool_call|>...<|tool_call_end|>`,
 *    `<|tool_call>...<tool_call|>`) are removed wholesale — the payload
 *    (JSON, "call: get_battery{}") is never user-facing.
 *  - Internal reasoning/control blocks (`<tool_call>...</tool_call>`,
 *    `<function_call>...</function_call>`, `<reasoning>...</reasoning>`,
 *    `<|im_start|>...<|im_end|>`) are dropped together with their content.
 *  - Standalone control tags and exact control tokens (`<|im_start|>`,
 *    ` thinking`, ` response`, `<bos>`, `<pad>`, ...) are stripped while
 *    normal prose survives untouched ("2 < 3" stays, `<b>bold</b>` stays).
 *  - Malformed or partial special tokens (`<tool_call` without `>`,
 *    `<|im_star`, `<function_cal`, ...) are detected and removed.
 *  - Invalid UTF-8 markers (`\uFFFD`) and byte-fallback tokens (`<0x0A>`,
 *    `<0xD0>`, ...) are removed anywhere in the text.
 *  - Trailing tokenizer artifacts left after generation stops (`и_`, `_`,
 *    `<`, `>`, `|>`, ...) are trimmed from the END of the response while
 *    legitimate multilingual text ("Привет", "Книга и") survives untouched.
 *
 * Streaming callers must use [streamingReady] on the ACCUMULATED buffer: it
 * holds back any trailing half-open control tag so a partial `<tool_call`
 * can never flash into the UI, holds back trailing artifact runs (a partial
 * byte-fallback char can never flash), and only emits once the tag either
 * closes or is proven dead. Sanitization runs on the accumulated text, never
 * on per-token fragments (a control block is almost always split across
 * fragments).
 */
object OutputSanitizer {

    private val BLOCK_NAMES = listOf(
        "tool_call", "tool_calls", "function_call", "function_calls",
        "reasoning", "reason", "think", "thinking", "thought", "thoughts",
        "analysis", "reflection", "scratchpad", "inner_monologue",
        "chain_of_thought", "plan", "output", "answer"
    )

    private val BLOCK_REGEX = Regex(
        "<\\s*(${BLOCK_NAMES.joinToString("|")})\\b[^>]*>(.*?)<\\s*/\\s*\\1\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** Gemma-4-style XML close variant: `<tool_call>call: name{}<tool_call|>`. */
    private val BLOCK_PIPE_CLOSE_REGEX = Regex(
        "<\\s*(${BLOCK_NAMES.joinToString("|")})\\b[^>]*>(.*?)<\\s*/?\\s*\\1\\s*\\|>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** Open internal block tag whose payload runs to the end with no close. */
    private val UNCLOSED_BLOCK_REGEX = Regex(
        "<\\s*(${BLOCK_NAMES.joinToString("|")})\\b[^>]*>[^<]*$",
        RegexOption.IGNORE_CASE
    )

    private val IM_START_END_REGEX = Regex(
        "<\\|im_start\\|[^>]*>.*?<\\|im_end\\|>", RegexOption.DOT_MATCHES_ALL
    )

    private val START_END_TURN_REGEX = Regex(
        "<\\s*start_of_turn\\b[^>]*>.*?<\\s*/?\\s*(?:end_of_turn|start_of_turn)\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val TAG_REGEX = Regex(
        "<\\s*/?\\s*(?:${BLOCK_NAMES.joinToString("|")}|" +
            "im_start|im_end|endoftext|end_of_text|end_of_turn|assistant|user|system|" +
            "bos|eos|pad|unk|start_of_turn|end_of_turn|start_header_id|end_header_id|" +
            "eot_id|tool_call_start|tool_call_end|done|end)\\s*/?\\s*>",
        RegexOption.IGNORE_CASE
    )

    private val CONTROL_TOKENS: List<String> = listOf(
        "<|im_start|>", "<|im_end|>", "<|endoftext|>", "<|end_of_text|>",
        "<|end_of_turn|>", "<|assistant|>", "<|user|>", "<|system|>",
        "<|start_header_id|>", "<|end_header_id|>", "<|eot_id|>",
        "<|tool_call_start|>", "<|tool_call_end|>", "<|tool_call|>",
        "<tool_call|>", "<|tool_call>",
        "<|think|>", "<|thinking|>", "<|reasoning|>", "<|answer|>",
        "<|output|>", "<|done|>", "<|end|>", "<|n|>", "<|message|>",
        "<|assistant_name|>", "<|user_name|>",
        "<start_of_turn>", "<end_of_turn>", "<|start_of_turn|>", "<|end_of_turn|>",
        "<bos>", "<eos>", "<pad>", "<unk>", "<s>", "</s>",
        "<human>", "<bot>", "<assistant>", "<user>", "<system>",
        "<|human|>", "<|bot|>", "<|user|>",
        "<fim_prefix>", "<fim_middle>", "<fim_suffix>"
    ).sortedByDescending { it.length }.distinct()

    /** SentencePiece byte-fallback tokens: `<0x0A>`, `<0xD0>`, `<0x1F600>`. */
    private val BYTE_FALLBACK_TOKEN_REGEX = Regex("<0x[0-9A-Fa-f]{1,4}>")

    /**
     * A serialized structured-response element: `{"type":"text","text":"…"}`.
     * Internal message objects (LiteRT-LM contents, cloud content parts,
     * tool-response payloads) must never be rendered as chat text — when one
     * leaks into a response it is flattened to its text field here.
     */
    private val TEXT_ELEMENT_REGEX = Regex(
        """\{\s*"type"\s*:\s*"text"\s*,\s*"text"\s*:\s*"((?:[^"\\]|\\.)*)"\s*\}"""
    )

    /** A whole serialized contents array: `[ …text elements… ]`. */
    private val SERIALIZED_CONTENTS_ARRAY_REGEX = Regex(
        """\[\s*(?:${TEXT_ELEMENT_REGEX.pattern}\s*,?\s*)+\]"""
    )

    /** A single standalone text-content object. */
    private val SINGLE_TEXT_ELEMENT_REGEX = Regex(
        """^\s*\{\s*"type"\s*:\s*"text"\s*,\s*"text"\s*:\s*"((?:[^"\\]|\\.)*)"\s*\}\s*$"""
    )

    /** The UTF-8 replacement character — the visible mark of an invalid sequence. */
    private const val INVALID_UTF8_MARKER = '\uFFFD'

    /** SentencePiece word-start marker (never valid in decoded assistant text). */
    private const val SP_WORD_START_MARKER = '\u2581'

    /**
     * llama.cpp thinking-channel markers ` thinking` / ` response` — stripped
     * ONLY at a line start (the position the template emits them), so normal
     * prose like "I was thinking about your response" is never corrupted.
     */
    private val THINK_CHANNEL_REGEX = Regex("(^|\\n)\\s*(thinking|response)(?=\\s|$)")

    /** A trailing malformed/partial token: `<` + name chars with no closing `>`. */
    private val PARTIAL_REGEX = Regex("<(?:\\|?[A-Za-z][A-Za-z0-9_|]*|/\\s*[A-Za-z])[^>]*$")

    /**
     * A mid-string malformed/partial control-token OPEN: a known control tag
     * name that is NOT closed by `>` right after the name (e.g. `<tool_call`
     * followed by a newline or plain text). Complete tags (`<tool_call>`)
     * are excluded via the `(?!>)` lookahead — they are removed by [TAG_REGEX]
     * (or the native scanner / exact token list) instead.
     */
    private val PARTIAL_OPEN_REGEX = Regex(
        "<(?:\\|)?(?:tool_call|tool_calls|function_call|function_calls|" +
            "reasoning|thinking|think|analysis|reflection|scratchpad|" +
            "chain_of_thought|inner_monologue|im_start|im_end|tool_call_start|" +
            "tool_call_end|eot_id|endoftext|end_of_text|end_of_turn|" +
            "start_of_turn|end_of_turn|start_header_id|end_header_id)" +
            "[A-Za-z0-9_|]*+(?!>)"
    )

    /** A `<\w`-like sequence that may grow into a control token while streaming. */
    private val TAG_LIKE_REGEX = Regex("<(?:\\|?[A-Za-z])")

    /** Instruction appended when a sanitized response came back EMPTY. */
    const val PLAIN_TEXT_RETRY_INSTRUCTION: String =
        "Your previous response contained no usable text (it consisted only of " +
            "control tokens, tags or tool-call markers). Respond in plain text " +
            "only: no XML tags, no special tokens, no tool-call markers, no " +
            "markup of any kind — just a natural-language answer."

    /** Instruction appended when a model calls a tool but no executor exists. */
    const val NO_TOOL_EXECUTOR_INSTRUCTION: String =
        "Tool calls are not available in this mode. Ignore your tool-calling " +
            "capability and answer the user directly in plain text without " +
            "using any tools or markers."

    fun sanitize(text: String): String {
        if (text.isBlank()) return ""
        var result = text
        // Structured-response flattening FIRST: an internal message object
        // that leaked this far is converted to plain text before any of the
        // tag/token passes below could mangle its JSON shape.
        result = flattenStructuredResponse(result)
        result = NativeToolCallScanner.strip(result)
        result = BLOCK_REGEX.replace(result, "")
        result = BLOCK_PIPE_CLOSE_REGEX.replace(result, "")
        result = IM_START_END_REGEX.replace(result, "")
        result = START_END_TURN_REGEX.replace(result, "")
        // Partial tokens BEFORE standalone tags: an unclosed internal open
        // (`<tool_call>payload` with no close) must swallow its payload tail
        // to the end, while a COMPLETE `<tool_call>` tag is left for TAG_REGEX.
        result = PARTIAL_REGEX.replace(result, "")
        result = PARTIAL_OPEN_REGEX.replace(result, "")
        result = UNCLOSED_BLOCK_REGEX.replace(result, "")
        result = THINK_CHANNEL_REGEX.replace(result, "")
        result = TAG_REGEX.replace(result, "")
        for (token in CONTROL_TOKENS) {
            if (token in result) result = result.replace(token, "")
        }
        result = BYTE_FALLBACK_TOKEN_REGEX.replace(result, "")
        result = result.replace(INVALID_UTF8_MARKER.toString(), "")
        return trimTrailingArtifacts(result.trim())
    }

    /**
     * Removes tokenizer artifacts glued to the END of a finished response.
     *
     * Generation stops mid-token far more often than mid-word: the native
     * runtime can emit a byte-fallback char (`и_`), a bare `_`/`▁`, the
     * replacement char (`�`) for a truncated UTF-8 sequence, or a fragment of
     * a special token (`<`, `>`, `<_`, `|>`). These are stripped ONLY at the
     * tail — legitimate text inside the response is never touched:
     *  - "Привет"        (all-Cyrillic)  -> unchanged
     *  - "Книга и"       (и after space) -> unchanged
     *  - "2 < 3"         (comparison)    -> unchanged
     *  - "https://x.com/" (URL slash)    -> unchanged
     *  - "youи_"         (byte fallback) -> "you"
     *  - "Sure!<_"       (tag fragment)  -> "Sure!"
     *  - "Hello�"        (invalid bytes) -> "Hello"
     */
    fun trimTrailingArtifacts(text: String): String {
        var end = text.length
        while (end > 0) {
            val c = text[end - 1]
            val prev = if (end >= 2) text[end - 2] else '\u0000'
            if (c == '>' && prev == '|') {
                // Leftover close fragment `|>` — drop both chars.
                end -= 2
                continue
            }
            if (!isTrailingArtifact(text, end)) break
            end--
        }
        return text.substring(0, end)
    }

    /**
     * True when the character at [endExclusive] - 1 is a trailing tokenizer
     * artifact that must never be shown. [endExclusive] is the length of the
     * (possibly already-trimmed) prefix, so "prev" walks one char further
     * back on every iteration of a multi-char artifact run.
     */
    private fun isTrailingArtifact(text: String, endExclusive: Int): Boolean {
        if (endExclusive <= 0) return false
        val c = text[endExclusive - 1]
        val prev = if (endExclusive >= 2) text[endExclusive - 2] else '\u0000'
        return c == INVALID_UTF8_MARKER ||
            c == '_' || c == SP_WORD_START_MARKER ||
            c == '<' ||
            (c == '>' && !prev.isLetterOrDigit()) ||
            (c == '|' && (prev == '>' || prev == '<' || prev == '|')) ||
            (c == '/' && prev == '<') ||
            (c == '\\' && prev == '<') ||
            // Stray non-ASCII byte-fallback char glued to an ASCII word:
            // "youи_" -> "you", while a trailing "и" after a space or after
            // another non-ASCII letter ("книга и", "Привет") stays.
            (c.code > 0x7F && prev.isLetterOrDigit() && prev.code <= 0x7F)
    }

    /**
     * Start index of the trailing artifact run in [text], or -1 when the
     * buffer ends on clean text. Mirrors [trimTrailingArtifacts] so the
     * streamed prefix is always a stable prefix of the final sanitized text.
     */
    private fun artifactRunStart(text: String): Int {
        var start = text.length
        while (start > 0) {
            val c = text[start - 1]
            val prev = if (start >= 2) text[start - 2] else '\u0000'
            if (c == '>' && prev == '|') {
                start -= 2
                continue
            }
            if (!isTrailingArtifact(text, start)) break
            start--
        }
        return if (start < text.length) start else -1
    }

    /** True when [text] is blank AFTER sanitization. */
    fun isBlankAfterSanitization(text: String): Boolean = sanitize(text).isBlank()

    /**
     * Converts structured message objects into plain chat text.
     *
     * `[{"type":"text","text":"Hello"}]` → `Hello`
     * `[{"type":"text","text":"Hi"},{"type":"text","text":"!"}]` → `Hi!`
     * A single standalone object keeps only its text field.
     *
     * The pattern requires the exact serialized content-element shape, so
     * ordinary prose (even JSON examples inside code blocks are protected by
     * requiring the FULL array/object match) is never touched. Multiple
     * matches are all flattened; JSON escapes in the text field are decoded.
     */
    fun flattenStructuredResponse(text: String): String {
        if (!text.contains("\"type\"")) return text
        var result = SERIALIZED_CONTENTS_ARRAY_REGEX.replace(text) { m ->
            buildString {
                for (el in TEXT_ELEMENT_REGEX.findAll(m.value)) {
                    append(unescapeJsonString(el.groupValues[1]))
                }
            }
        }
        val single = SINGLE_TEXT_ELEMENT_REGEX.find(result)
        if (single != null) {
            result = unescapeJsonString(single.groupValues[1])
        }
        return result
    }

    /** Minimal JSON string unescape for extracted text fields. */
    private fun unescapeJsonString(s: String): String {
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
                        val code = if (hex.length == 4) hex.toIntOrNull(16) else null
                        if (code != null) {
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

    /**
     * Streaming-safe sanitization of the ACCUMULATED buffer. Any trailing
     * half-open control tag is held back (the UI never shows a partial
     * `<tool_call`), and trailing artifact runs are held back too (a partial
     * byte-fallback char like `и_` can never flash). The result is always a
     * stable prefix of what [sanitize] will eventually produce.
     */
    fun streamingReady(accumulated: String): String {
        if (accumulated.isBlank()) return ""
        val tagHold = holdbackStart(accumulated)
        val artifactHold = artifactRunStart(accumulated)
        val hold = when {
            tagHold < 0 -> artifactHold
            artifactHold < 0 -> tagHold
            else -> minOf(tagHold, artifactHold)
        }
        val safe = if (hold >= 0) accumulated.substring(0, hold) else accumulated
        return sanitize(safe)
    }

    /**
     * Index from which the streamed text must be held back, or -1 when the
     * whole buffer is safe. A trailing `<\w`-sequence that has not closed
     * with `>` yet is held back; a complete OPEN internal tag whose block has
     * not closed yet is held back too (its payload must never surface).
     */
    private fun holdbackStart(text: String): Int {
        var from = 0
        while (from < text.length) {
            val lt = text.indexOf('<', from)
            if (lt < 0) return -1
            if (!TAG_LIKE_REGEX.containsMatchIn(text.substring(lt))) {
                from = lt + 1
                continue
            }
            val gt = text.indexOf('>', lt + 1)
            if (gt < 0) return lt
            val tag = text.substring(lt, gt + 1)
            if (isOpenTag(tag) && !blockClosed(text, gt + 1, tag)) return lt
            from = gt + 1
        }
        return -1
    }

    private fun isOpenTag(tag: String): Boolean {
        val normalized = tag.trim().lowercase()
        return OPEN_TAGS.any { normalized == it }
    }

    private fun blockClosed(text: String, from: Int, openTag: String): Boolean {
        for (close in closeMarkers(openTag)) {
            if (text.indexOf(close, from) >= 0) return true
        }
        return false
    }

    private fun closeMarkers(openTag: String): List<String> = when (openTag) {
        "<|tool_call_start|>", "<|tool_call|>" -> listOf("<|tool_call_end|>")
        "<|tool_call>" -> listOf("<tool_call|>", "<|tool_call|>", "<|tool_call>")
        "<|im_start|>" -> listOf("<|im_end|>")
        "<start_of_turn>" -> listOf("<end_of_turn>", "<|end_of_turn|>")
        else -> {
            val name = openTag.trim('<', '>').trim()
            listOf("</$name>", "<$name|>")
        }
    }

    private val OPEN_TAGS = listOf(
        "<tool_call>", "<tool_calls>", "<function_call>", "<function_calls>",
        "<reasoning>", "<reason>", "<thinking>", "<think>", "<thought>",
        "<thoughts>", "<analysis>", "<reflection>", "<scratchpad>",
        "<inner_monologue>", "<chain_of_thought>", "<plan>", "<output>",
        "<answer>", "<|tool_call_start|>", "<|tool_call|>", "<|tool_call>",
        "<|im_start|>", "<start_of_turn>"
    )
}