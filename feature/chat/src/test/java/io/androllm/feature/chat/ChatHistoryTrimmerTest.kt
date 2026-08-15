package io.androllm.feature.chat

import io.androllm.core.models.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [ChatHistoryTrimmer] — the sliding-window policy that
 * keeps a chat prompt inside the model's context window. These pin the exact
 * trimming invariants so a regression (e.g. dropping the current user prompt,
 * or producing two consecutive user messages) fails here first.
 */
class ChatHistoryTrimmerTest {

    private fun user(content: String, ts: Long = 0): ChatMessage =
        ChatMessage("u$ts", "c1", MessageRole.USER, content, ts)

    private fun assistant(content: String, ts: Long = 0): ChatMessage =
        ChatMessage("a$ts", "c1", MessageRole.ASSISTANT, content, ts)

    @Test
    fun `empty history stays empty`() {
        assertTrue(ChatHistoryTrimmer.trim(emptyList(), 4096, 1024).isEmpty())
    }

    @Test
    fun `history with no user message is dropped entirely`() {
        val history = listOf(assistant("orphan reply"))
        assertTrue(ChatHistoryTrimmer.trim(history, 4096, 1024).isEmpty())
    }

    @Test
    fun `history under budget passes through unchanged`() {
        val history = listOf(
            user("hello", 1),
            assistant("hi there", 2),
            user("how are you?", 3)
        )
        val trimmed = ChatHistoryTrimmer.trim(history, 4096, 1024)
        assertEquals(history, trimmed)
    }

    @Test
    fun `oldest messages are dropped first`() {
        // 5 messages, budget fits only the last 3 (token cost: current q 8,
        // second a 10, second q 10, old a 10, old q 10).
        val history = listOf(
            user("old exchange question one", 1),
            assistant("old exchange answer one", 2),
            user("second exchange question", 3),
            assistant("second exchange answer", 4),
            user("current question", 5)
        )
        // Budget = 100 - 68 = 32: keeps the tail 3 (28 tokens), the 4th
        // message (old answer, 38 total) would overflow → dropped with its
        // user prompt.
        val trimmed = ChatHistoryTrimmer.trim(history, contextLength = 100, reservedOutputTokens = 68)
        assertEquals(listOf(history[2], history[3], history[4]), trimmed)
    }

    @Test
    fun `current user prompt is always kept even when it alone overflows`() {
        val hugePrompt = user("x".repeat(10_000), 1)
        val history = listOf(user("old", 0), hugePrompt)
        val trimmed = ChatHistoryTrimmer.trim(history, contextLength = 512, reservedOutputTokens = 256)
        assertEquals(listOf(hugePrompt), trimmed)
    }

    @Test
    fun `zero budget keeps only the current prompt`() {
        val history = listOf(
            user("old question", 1),
            assistant("old answer", 2),
            user("current question", 3)
        )
        val trimmed = ChatHistoryTrimmer.trim(history, contextLength = 100, reservedOutputTokens = 100)
        assertEquals(listOf(history[2]), trimmed)
    }

    @Test
    fun `trim never starts with an orphaned assistant reply`() {
        // Budget drops the FIRST user message but its reply still fits — the
        // renderer would otherwise pair an assistant turn with the wrong user
        // turn. Token cost: dropped q 8, orphaned reply 8, kept q 8.
        val history = listOf(
            user("dropped question", 1),
            assistant("orphaned reply", 2),
            user("kept question", 3)
        )
        // Budget = 60 - 40 = 20: fits kept q + orphaned reply (16), not the
        // dropped question (24 total) → the orphaned reply must be dropped too.
        val trimmed = ChatHistoryTrimmer.trim(history, contextLength = 60, reservedOutputTokens = 40)
        assertEquals(listOf(history[2]), trimmed)
        assertEquals(MessageRole.USER, trimmed.first().role)
        assertEquals(history[2], trimmed.last())
        assertTrue(trimmed.none { it.id == "u1" || it.id == "a2" })
    }

    @Test
    fun `system overhead and output reserve shrink the window`() {
        val history = listOf(
            user("a".repeat(100), 1),
            assistant("b".repeat(100), 2),
            user("c".repeat(100), 3)
        )
        val withOverhead = ChatHistoryTrimmer.trim(
            history, contextLength = 400, reservedOutputTokens = 100, systemTokenOverhead = 200
        )
        val withoutOverhead = ChatHistoryTrimmer.trim(
            history, contextLength = 400, reservedOutputTokens = 100, systemTokenOverhead = 0
        )
        assertTrue(
            "overhead must trim more aggressively: $withOverhead vs $withoutOverhead",
            withOverhead.size <= withoutOverhead.size
        )
    }

    @Test
    fun `output reserve keeps headroom for the response`() {
        val history = listOf(
            user("question one", 1),
            assistant("answer one", 2),
            user("question two", 3),
            assistant("answer two", 4),
            user("question three", 5)
        )
        val tight = ChatHistoryTrimmer.trim(history, contextLength = 100, reservedOutputTokens = 90)
        val loose = ChatHistoryTrimmer.trim(history, contextLength = 100, reservedOutputTokens = 10)
        assertTrue("larger reserve must keep less history", tight.size <= loose.size)
        assertEquals(history.last(), loose.last())
    }

    @Test
    fun `estimate tokens is never zero and grows with text length`() {
        val short = ChatHistoryTrimmer.estimateTokens("hi")
        val long = ChatHistoryTrimmer.estimateTokens("x".repeat(1000))
        assertTrue(short > 0)
        assertTrue(long > short)
    }

    @Test
    fun `CJK text is weighted at roughly one token per character`() {
        // 20 CJK characters. A flat chars/4 heuristic would estimate only
        // ~9 tokens (20/4 + overhead) — 2-4x under-counted for Chinese — and
        // the trimmed window could still overflow nCtx (freeze). The CJK-aware
        // estimator must charge ~1 token per non-ASCII char.
        val cjk = "这是二十个中文字符的测试文本内容示例啊哈" // 20 chars
        assertEquals(24, ChatHistoryTrimmer.estimateTokens(cjk))

        // Mixed ASCII + CJK: "Hello 你好世界 123" = 9 ASCII chars (~3 tokens)
        // + 4 CJK chars (4 tokens) + overhead (4) = 11.
        val mixed = "Hello 你好世界 123"
        val mixedEstimate = ChatHistoryTrimmer.estimateTokens(mixed)
        assertTrue("mixed CJK must not be under-estimated: $mixedEstimate", mixedEstimate >= 11)
    }
}
