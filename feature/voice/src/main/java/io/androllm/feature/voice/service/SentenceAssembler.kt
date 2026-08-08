package io.androllm.feature.voice.service

/**
 * Turns a token stream into complete sentences so TTS can start speaking the
 * first sentence while the model is still generating the rest.
 */
class SentenceAssembler {

    private val buffer = StringBuilder()

    /**
     * Appends a delta and returns any complete sentences that were formed.
     * Sentences are split on sentence-final punctuation; a small trailing
     * buffer (up to [MIN_COMPLETE_LENGTH] chars) is held back so short
     * sentences like "Yes." don't wait forever.
     */
    fun feed(delta: String): List<String> {
        buffer.append(delta)
        val sentences = mutableListOf<String>()
        val text = buffer.toString()
        var start = 0
        for (i in text.indices) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?' || c == '\n' || c == '。' || c == '！' || c == '？') {
                val end = if (c == '\n') i else i + 1
                val sentence = text.substring(start, end).trim()
                if (sentence.isNotEmpty() && sentence.length >= MIN_COMPLETE_LENGTH) {
                    sentences.add(sentence)
                }
                start = i + 1
            }
        }
        buffer.setLength(0)
        buffer.append(text.substring(start))
        return sentences
    }

    /** Remaining partial text after the stream ended (still spoken). */
    fun drain(): String {
        val leftover = buffer.toString().trim()
        buffer.setLength(0)
        return leftover
    }

    companion object {
        private const val MIN_COMPLETE_LENGTH = 3
    }
}
