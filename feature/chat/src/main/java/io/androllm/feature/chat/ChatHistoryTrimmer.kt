package io.androllm.feature.chat

import io.androllm.core.models.MessageRole
import kotlin.math.ceil
import kotlin.math.max

/**
 * Sliding-window history trimmer that keeps a chat prompt inside the model's
 * context window.
 *
 * Without trimming, a long conversation eventually exceeds nCtx: the native
 * engine either fails to decode (context overflow — "model never starts" /
 * freeze) or decodes garbage at the clamped tail. This trimmer enforces a
 * token budget **before** the prompt is rendered by dropping the OLDEST
 * messages first — the standard sliding-window policy — while always
 * preserving the current user prompt so the assistant never answers a
 * different question.
 *
 * Pure and side-effect free: the exact policy is exercised by unit tests in
 * `ChatHistoryTrimmerTest`.
 */
object ChatHistoryTrimmer {

    /**
     * Approximate tokens in [text], plus a fixed per-message overhead for the
     * role markers and framing the chat template wraps every message in.
     *
     * Weighting: ASCII ~4 chars/token; non-ASCII (CJK, emoji, accents) costs
     * 1 token per character — the common BPE tokenizers spend 1–2 tokens per
     * CJK character, so a flat `chars / 4` would UNDER-estimate Chinese,
     * Japanese and Korean prompts by 2–4× and the trimmed window could still
     * overflow nCtx (freeze). Deliberately conservative (never under-estimates)
     * so the rendered prompt stays comfortably inside the context window; the
     * native engine owns the exact tokenizer.
     */
    fun estimateTokens(text: String): Int {
        var asciiChars = 0
        var wideChars = 0
        for (c in text) {
            if (c.code < 0x80) asciiChars++ else wideChars++
        }
        return max(1, ceil(asciiChars / CHARS_PER_TOKEN).toInt() + wideChars) + ROLE_OVERHEAD_TOKENS
    }

    /**
     * Returns the chronological sub-list of [history] that fits inside
     * [contextLength] minus [reservedOutputTokens] (tokens kept free for the
     * response) and [systemTokenOverhead] (memory context, tool
     * advertisement, chat-template framing).
     *
     * Invariants (all unit-tested):
     * - The current (last) user message is ALWAYS kept, even when it alone
     *   exceeds the budget — dropping the user's actual question would make
     *   the assistant answer the wrong thing.
     * - Oldest messages are dropped first (the window slides over the tail).
     * - The result starts with a user message: a trim can orphan a leading
     *   assistant reply whose user prompt was dropped, and the chat template
     *   renderer expects alternating roles starting with user.
     */
    fun trim(
        history: List<ChatMessage>,
        contextLength: Int,
        reservedOutputTokens: Int,
        systemTokenOverhead: Int = 0
    ): List<ChatMessage> {
        if (history.isEmpty()) return emptyList()

        val lastUserIdx = history.indexOfLast { it.role == MessageRole.USER }
        if (lastUserIdx < 0) return emptyList()

        val budget = (contextLength - reservedOutputTokens - systemTokenOverhead)
            .coerceAtLeast(0)

        // Budget exhausted before any history could fit: still keep the
        // current prompt — the engine clamps the prompt at nCtx.
        if (budget <= 0) return listOf(history[lastUserIdx])

        // Greedy tail walk: newest first, stop when adding the next (older)
        // message would exceed the budget. The last user message is mandatory.
        val kept = ArrayDeque<ChatMessage>()
        var used = 0
        for (i in history.indices.reversed()) {
            val message = history[i]
            val cost = estimateTokens(message.content)
            if (i != lastUserIdx && used + cost > budget) break
            kept.addFirst(message)
            used += cost
        }

        // Drop an orphaned leading assistant reply (its user prompt was
        // trimmed away) so the rendered prompt alternates user→assistant
        // from the first message. The mandatory last user message is never
        // removed by this loop.
        while (kept.isNotEmpty() && kept.first().role != MessageRole.USER) {
            kept.removeFirst()
        }
        return kept.toList()
    }

    private const val CHARS_PER_TOKEN = 4.0
    private const val ROLE_OVERHEAD_TOKENS = 4
}
