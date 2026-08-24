package io.androllm.engine.compat

/**
 * Detects a completed stop sequence in a token stream that arrives in
 * per-token FRAGMENTS (LiteRT-LM never delivers cumulative text).
 *
 * A stop sequence can be split across fragment boundaries (the model emits
 * `<|im_end|>` as two fragments `<|im_` + `end|>`), so the tracker keeps a
 * rolling tail of the raw stream ([TAIL_LIMIT] chars) and checks it after
 * every fragment. The moment the last character of a stop sequence arrives,
 * the tracker reports it together with the ABSOLUTE index at which the stop
 * began — the caller cuts the accumulated raw text there, never streaming a
 * partial stop token to the UI.
 *
 * First match wins: generation must stop on the FIRST stop sequence, and
 * every later fragment is ignored by the caller anyway.
 */
class StopSequenceTracker(
    stopSequences: List<String>,
    private val tailLimit: Int = TAIL_LIMIT
) {

    private val stops: List<String> = stopSequences
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedByDescending { it.length }

    /** Pre-computed holdback length (longest stop - 1). */
    private val _holdbackLength: Int = (stops.maxOfOrNull { it.length } ?: 1) - 1

    private val tail = StringBuilder()
    private var fedChars = 0L

    private var matchedStop: String? = null
    private var matchedStopStart = -1L

    /**
     * Number of raw chars a stream emitter must hold back before emitting:
     * the longest stop sequence minus one. Holding back that many chars
     * guarantees a stop split across fragment boundaries can never be
     * streamed — its leading chars stay un-emitted until the next fragment
     * proves they are not part of a stop.
     */
    val holdbackLength: Int get() = _holdbackLength

    /** True once a stop sequence has been completed (first match wins). */
    val isStopped: Boolean get() = matchedStop != null

    /** The stop sequence that terminated generation, null until matched. */
    val matched: String? get() = matchedStop

    /**
     * Absolute character index (in the raw stream) where the matched stop
     * sequence begins; -1 until a stop is matched. The raw text before this
     * index is the complete generation.
     */
    val stopStartIndex: Long get() = matchedStopStart

    /**
     * Feeds one decoded fragment of the raw stream. Returns the matched stop
     * sequence when this fragment completed one, null otherwise. After a
     * match the tracker is latched: further calls return the same stop.
     */
    fun feed(fragment: String): String? {
        if (matchedStop != null) return matchedStop
        if (fragment.isEmpty()) return null
        tail.append(fragment)
        fedChars += fragment.length
        // Keep only the rolling tail: the longest stop sequence is far
        // shorter than TAIL_LIMIT, so a stop split across fragment boundaries
        // is always fully inside the window.
        if (tail.length > tailLimit) {
            tail.delete(0, tail.length - tailLimit)
        }
        for (stop in stops) {
            val idx = tail.indexOf(stop)
            if (idx >= 0) {
                matchedStop = stop
                matchedStopStart = fedChars - tail.length + idx
                return stop
            }
        }
        return null
    }

    companion object {
        /**
         * Rolling window. The longest real stop sequence is `<end_of_turn>`
         * (13 chars) and the 64-char window is an order of magnitude larger —
         * generous slack so a stop split across many small fragments is still
         * detected the moment it completes.
         */
        const val TAIL_LIMIT = 64
    }
}