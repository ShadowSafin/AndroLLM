package io.androllm.engine.core

/**
 * Detects pathological generation loops in a streaming token path.
 *
 * A broken sampler / corrupt context can make a model emit the same token
 * forever or repeat the same phrase indefinitely without ever sampling EOS.
 * maxTokens and the hard timeout eventually stop such runs — minutes later.
 * This guard detects BOTH degenerate shapes within seconds and lets the
 * caller terminate the decode cleanly (partial text preserved, history
 * intact):
 *
 *  1. IDENTICAL FRAGMENT LOOP — the same non-trivial fragment repeated
 *     [MAX_IDENTICAL_FRAGMENTS] times in a row (single-token repetition).
 *  2. PHRASE/SENTENCE LOOP — the tail of the stream consists of the same
 *     unit (4..96 chars) repeated [MIN_UNIT_REPEATS] times consecutively,
 *     covering at least [MIN_LOOP_CHARS] chars (multi-token cycles that are
 *     never byte-identical per fragment).
 *
 * Feeding is O(1) amortized; the phrase scan runs only every
 * [SCAN_INTERVAL_CHARS] new chars over a bounded window, so the hot decoding
 * path stays fast. The guard is single-threaded by design: feed it from the
 * same callback that receives fragments.
 */
class GenerationLoopGuard {

    private var lastFragment: String? = null
    private var identicalRun = 0

    private val window = StringBuilder()
    private var charsSinceScan = 0
    private var triggered = false
    private var triggerDetail: String? = null

    /** True once a pathological loop has been detected (latched). */
    val isLooping: Boolean get() = triggered

    /** Human-readable description of the detected loop (null until latched). */
    val detail: String? get() = triggerDetail

    /**
     * Feeds one raw generated fragment. Returns true the first time a loop
     * is detected (and on every call afterwards — latched). Empty and
     * whitespace-only fragments never contribute.
     */
    fun feed(fragment: String): Boolean {
        if (triggered) return true
        if (fragment.isEmpty() || fragment.isBlank()) return false

        // --- Identical-fragment run -------------------------------------
        if (fragment == lastFragment && !isTrivialFragment(fragment)) {
            identicalRun++
            if (identicalRun >= MAX_IDENTICAL_FRAGMENTS) {
                latch("identical fragment repeated $identicalRun times: '${fragment.take(32)}'")
                return true
            }
        } else {
            lastFragment = fragment
            identicalRun = 1
        }

        // --- Phrase/sentence cycle --------------------------------------
        window.append(fragment)
        charsSinceScan += fragment.length
        if (window.length > WINDOW_CHARS) {
            window.delete(0, window.length - WINDOW_CHARS)
        }
        if (charsSinceScan >= SCAN_INTERVAL_CHARS) {
            charsSinceScan = 0
            scanForCycle()?.let { latch(it); return true }
        }
        return false
    }

    /**
     * Finds a unit of length 4..[MAX_UNIT_CHARS] whose consecutive repeats
     * fill the recent tail ([MIN_UNIT_REPEATS] times, >= [MIN_LOOP_CHARS]
     * chars). Returns a description when found, null otherwise.
     */
    private fun scanForCycle(): String? {
        val text = window.toString()
        val tailLimit = text.length
        var unitLen = MIN_UNIT_CHARS
        while (unitLen <= MAX_UNIT_CHARS && unitLen * MIN_UNIT_REPEATS <= tailLimit) {
            val unit = text.substring(tailLimit - unitLen)
            if (!unit.isBlank()) {
                var repeats = 1
                var pos = tailLimit - unitLen
                while (pos - unitLen >= 0 && text.startsWith(unit, pos - unitLen)) {
                    repeats++
                    pos -= unitLen
                }
                val coveredChars = repeats * unitLen
                if (repeats >= MIN_UNIT_REPEATS && coveredChars >= MIN_LOOP_CHARS) {
                    return "phrase '${unit.take(48)}' repeated $repeats times consecutively"
                }
            }
            unitLen++
        }
        return null
    }

    private fun latch(why: String) {
        triggered = true
        triggerDetail = why
    }

    /**
     * Fragments too small to be meaningful on their own (a lone space, dot,
     * comma, newline…) are exempt from the identical-run counter — healthy
     * prose legitimately repeats them.
     */
    private fun isTrivialFragment(f: String): Boolean =
        f.length <= 2 || f.all { it.isWhitespace() || it in ",.;:!?\n\r\t" }

    companion object {
        /** Consecutive identical non-trivial fragments before tripping. */
        const val MAX_IDENTICAL_FRAGMENTS = 48

        /** Minimum repeats of one phrase unit for the cycle detector. */
        const val MIN_UNIT_REPEATS = 8

        /** Smallest phrase unit considered (chars). Below this, normal words repeat. */
        const val MIN_UNIT_CHARS = 4

        /** Largest phrase unit considered (chars). */
        const val MAX_UNIT_CHARS = 96

        /** Covered tail length (chars) required to declare a phrase loop. */
        const val MIN_LOOP_CHARS = 64

        /** Bounded rolling window kept for the cycle scan. */
        const val WINDOW_CHARS = 512

        /** Run the (slightly heavier) cycle scan at most this often. */
        const val SCAN_INTERVAL_CHARS = 16
    }
}
