package io.androllm.feature.coding.workspace

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Per-workspace chat transcript persistence. */
class FileChatTranscriptStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = FileChatTranscriptStore(tmp.newFolder("sessions"))

    private fun msg(id: String, role: String, text: String) =
        TranscriptMessage(id = id, role = role, text = text, timestampMs = 1000L)

    @Test
    fun `save then load round-trips messages and history`() = runBlocking {
        val store = store()
        val transcript = CodingTranscript(
            workspaceId = "ws-1",
            messages = listOf(
                msg("1", "USER", "build me a site"),
                msg("2", "ASSISTANT", "done — index.html created")
            ),
            history = listOf(
                TranscriptHistoryItem(role = "user", content = "build me a site"),
                TranscriptHistoryItem(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(TranscriptToolCall(id = "call_1", name = "write_file", arguments = "{}"))
                ),
                TranscriptHistoryItem(role = "tool", content = "ok", toolCallId = "call_1"),
                TranscriptHistoryItem(role = "assistant", content = "done — index.html created")
            )
        )
        store.save("ws-1", transcript)

        val loaded = store.load("ws-1")
        assertEquals(2, loaded!!.messages.size)
        assertEquals("USER", loaded.messages[0].role)
        assertEquals(4, loaded.history.size)
        assertEquals("call_1", loaded.history[1].toolCalls!!.first().id)
        assertEquals("call_1", loaded.history[2].toolCallId)
    }

    @Test
    fun `load returns null when nothing was saved`() = runBlocking {
        assertNull(store().load("never-saved"))
    }

    @Test
    fun `clear removes the transcript`() = runBlocking {
        val store = store()
        store.save("ws-2", CodingTranscript(workspaceId = "ws-2", messages = listOf(msg("1", "USER", "hi"))))
        store.clear("ws-2")
        assertNull(store.load("ws-2"))
    }

    @Test
    fun `message list is capped to the most recent entries`() = runBlocking {
        val store = store()
        val many = (1..(FileChatTranscriptStore.MAX_MESSAGES + 50)).map { msg("m$it", "USER", "msg $it") }
        store.save("ws-3", CodingTranscript(workspaceId = "ws-3", messages = many))
        val loaded = store.load("ws-3")!!
        assertEquals(FileChatTranscriptStore.MAX_MESSAGES, loaded.messages.size)
        assertEquals("msg ${many.size}", loaded.messages.last().text)
    }

    @Test
    fun `oversized message text is truncated`() = runBlocking {
        val store = store()
        val huge = "x".repeat(FileChatTranscriptStore.MAX_MESSAGE_CHARS + 500)
        store.save("ws-4", CodingTranscript(workspaceId = "ws-4", messages = listOf(msg("1", "USER", huge))))
        val loaded = store.load("ws-4")!!
        assertTrue(loaded.messages[0].text.length <= FileChatTranscriptStore.MAX_MESSAGE_CHARS + 32)
        assertTrue(loaded.messages[0].text.contains("[truncated]"))
    }

    @Test
    fun `history trim starts on a user boundary`() = runBlocking {
        val store = store()
        val history = listOf(
            // Leading orphaned tool reply — must be dropped on trim.
            TranscriptHistoryItem(role = "tool", content = "orphan", toolCallId = "call_0"),
            TranscriptHistoryItem(role = "user", content = "real start"),
            TranscriptHistoryItem(role = "assistant", content = "answer")
        )
        store.save("ws-5", CodingTranscript(workspaceId = "ws-5", messages = listOf(msg("1", "USER", "hi")), history = history))
        val loaded = store.load("ws-5")!!
        assertEquals("user", loaded.history.first().role)
        assertEquals("real start", loaded.history.first().content)
        assertEquals(2, loaded.history.size)
    }

    @Test
    fun `workspace ids with special characters map to safe file names`() = runBlocking {
        val store = store()
        val id = "ext-ab/c:d*e"
        store.save(id, CodingTranscript(workspaceId = id, messages = listOf(msg("1", "USER", "hi"))))
        assertEquals(1, store.load(id)!!.messages.size)
    }
}
