package io.androllm.feature.voice.service

/**
 * A natural speech chunk paired with its absolute character offset in the
 * streamed answer text, so the overlay can highlight exactly the sentence
 * (and word) currently being spoken.
 *
 * [startOffset] points at the first character of [text] inside the answer
 * accumulated by the voice service. Offsets are only valid while the answer
 * text that produced them is still the latest answer shown.
 */
data class SpokenChunk(
    val text: String,
    val startOffset: Int
)

/**
 * Turns a token stream into natural speech chunks so TTS can begin speaking
 * while the model is still generating the rest.
 *
 * A chunk is emitted when ANY of these holds (whichever fires first):
 *  - a sentence-final punctuation mark (. ! ? \n 。！？) — preferred
 *  - a clause boundary (, ; : ，；) once the pending clause is long enough
 *  - roughly [MAX_WORDS] words have accumulated without any punctuation
 *
 * [flush] lets the consumer push the pending partial chunk on a silence gap
 * (no deltas for ~1s — the model paused mid-thought) or when the stream ends,
 * so speech never stalls waiting for a sentence that may never come.
 *
 * The consumer must call [flush] with `force = true` once at stream end so
 * the final partial sentence is never dropped.
 *
 * Every emitted chunk carries its absolute [SpokenChunk.startOffset] in the
 * answer stream (the service feeds [feed] the exact same deltas it appends to
 * the answer text, so offsets line up character-for-character).
 */
class SentenceAssembler(
    private val maxWords: Int = MAX_WORDS,
    private val minChunkChars: Int = MIN_CHUNK_CHARS
) {

    private val buffer = StringBuilder()

    /** Total characters fed so far — the absolute position of `buffer[0]` in the answer is `charsFed - buffer.length`. */
    private var charsFed = 0

    /**
     * Appends [delta] and returns any complete speech chunks that formed.
     * Also emits the whole pending buffer once it passes [maxWords] — long
     * streams without punctuation must still flow instead of growing forever.
     */
    fun feed(delta: String): List<SpokenChunk> {
        buffer.append(delta)
        charsFed += delta.length
        val text = buffer.toString()
        val chunks = mutableListOf<SpokenChunk>()
        var start = 0
        for (i in text.indices) {
            val c = text[i]
            if (isSentenceEnd(c)) {
                // Keep the punctuation in the spoken chunk (natural prosody).
                val end = if (c == '\n') i else i + 1
                emit(text, start, end, chunks)
                start = i + 1
            } else if (isClauseBoundary(c) && (i - start) >= minChunkChars) {
                emit(text, start, i + 1, chunks)
                start = i + 1
            }
        }
        buffer.setLength(0)
        buffer.append(text.substring(start))
        if (countWords(buffer) >= maxWords) {
            val whole = buffer.toString()
            val trimmed = whole.trim()
            buffer.setLength(0)
            if (trimmed.isNotEmpty()) {
                val leading = whole.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                chunks.add(SpokenChunk(trimmed, charsFed - whole.length + leading))
            }
        }
        return chunks
    }

    /**
     * Returns the pending partial chunk, or null when there is nothing worth
     * speaking yet. [force] returns even a short tail (used at stream end so
     * the final words are never dropped).
     */
    fun flush(force: Boolean = false): SpokenChunk? {
        val raw = buffer.toString()
        buffer.setLength(0)
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.length < minChunkChars && !force) return null
        val leading = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        return SpokenChunk(trimmed, charsFed - raw.length + leading)
    }

    private fun emit(text: String, start: Int, end: Int, out: MutableList<SpokenChunk>) {
        val raw = text.substring(start, end)
        val trimmed = raw.trim()
        if (trimmed.length < minChunkChars) return
        // `text` is the whole buffer, whose first char sits at `charsFed - text.length`
        // in the answer; the chunk starts at `start` plus any leading whitespace.
        val leading = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        out.add(SpokenChunk(trimmed, charsFed - text.length + start + leading))
    }

    private fun countWords(s: CharSequence): Int {
        if (s.isBlank()) return 0
        return s.split(Regex("\\s+")).size
    }

    private fun isSentenceEnd(c: Char): Boolean =
        c == '.' || c == '!' || c == '?' || c == '\n' || c == '。' || c == '！' || c == '？'

    private fun isClauseBoundary(c: Char): Boolean =
        c == ',' || c == ';' || c == ':' || c == '，' || c == '；'

    companion object {
        /** Hard word cap per chunk — long streams without punctuation must flow. */
        const val MAX_WORDS = 14

        /**
         * Minimum chunk length before it is spoken. Keep this LOW: numeric
         * answers ("10 + 10 = 20" chunked at punctuation) can form tiny
         * fragments like "10." — a threshold of 4+ silently dropped digits
         * from spoken math answers, so the user hears "plus equals" only.
         */
        const val MIN_CHUNK_CHARS = 2
    }
}
