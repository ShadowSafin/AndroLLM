package io.androllm.feature.coding.workspace

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One persisted chat line (mirror of the UI message, minus streaming state). */
@Serializable
data class TranscriptMessage(
    val id: String,
    val role: String,
    val text: String,
    val timestampMs: Long = 0L,
    val toolName: String? = null,
    val diff: String? = null,
    val failedCommand: String? = null
)

/** One persisted cloud-history tool call (assistant message with tool_calls). */
@Serializable
data class TranscriptToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

/** One persisted cloud-conversation entry (system prompt is rebuilt, not stored). */
@Serializable
data class TranscriptHistoryItem(
    val role: String,
    val content: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<TranscriptToolCall>? = null
)

/**
 * Everything needed to restore a coding chat exactly as the user left it:
 * the visible transcript plus the cloud conversation (so the model keeps its
 * context when the chat reopens).
 */
@Serializable
data class CodingTranscript(
    val workspaceId: String = "",
    val savedAtMs: Long = 0L,
    val messages: List<TranscriptMessage> = emptyList(),
    val history: List<TranscriptHistoryItem> = emptyList()
)

/**
 * Persistence boundary for coding chat transcripts. Keyed by workspace id so
 * every workspace folder keeps its own conversation. Production stores JSON
 * files under app storage; unit tests use a temp directory.
 */
interface ChatTranscriptStore {
    suspend fun save(workspaceId: String, transcript: CodingTranscript)
    suspend fun load(workspaceId: String): CodingTranscript?
    suspend fun clear(workspaceId: String)
}

/**
 * File-backed [ChatTranscriptStore]: one JSON file per workspace under [dir].
 *
 * Transcripts are bounded on save so a long session can never balloon the
 * file: the visible message list is capped at [MAX_MESSAGES] (each text/diff
 * truncated), and the cloud history is capped at [MAX_HISTORY] entries,
 * trimmed from the head down to a `user` boundary so the restored
 * conversation always starts cleanly (never mid tool-call exchange).
 */
class FileChatTranscriptStore(private val dir: File) : ChatTranscriptStore {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private fun fileFor(workspaceId: String): File {
        val safe = workspaceId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, "$safe.json")
    }

    override suspend fun save(workspaceId: String, transcript: CodingTranscript) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val bounded = CodingTranscript(
            workspaceId = workspaceId,
            savedAtMs = System.currentTimeMillis(),
            messages = transcript.messages
                .takeLast(MAX_MESSAGES)
                .map { m ->
                    m.copy(
                        text = cap(m.text, MAX_MESSAGE_CHARS),
                        diff = m.diff?.let { cap(it, MAX_DIFF_CHARS) }
                    )
                },
            history = trimHistory(transcript.history)
        )
        runCatching { fileFor(workspaceId).writeText(json.encodeToString(bounded)) }
        Unit
    }

    override suspend fun load(workspaceId: String): CodingTranscript? = withContext(Dispatchers.IO) {
        val file = fileFor(workspaceId)
        if (!file.exists()) return@withContext null
        runCatching { json.decodeFromString<CodingTranscript>(file.readText()) }.getOrNull()
    }

    override suspend fun clear(workspaceId: String) = withContext(Dispatchers.IO) {
        runCatching { fileFor(workspaceId).delete() }
        Unit
    }

    private fun trimHistory(history: List<TranscriptHistoryItem>): List<TranscriptHistoryItem> {
        val capped = history.takeLast(MAX_HISTORY).map { item ->
            item.copy(content = item.content?.let { cap(it, MAX_HISTORY_CHARS) })
        }
        // Drop leading entries until the conversation starts on a user message —
        // a head trim can otherwise leave an orphaned tool reply with no parent
        // assistant tool_calls, which providers reject.
        val start = capped.indexOfFirst { it.role == "user" && it.toolCallId == null }
        return if (start < 0) emptyList() else capped.subList(start, capped.size)
    }

    private fun cap(text: String, max: Int): String =
        if (text.length > max) text.take(max) + "\n…[truncated]" else text

    companion object {
        const val MAX_MESSAGES = 300
        const val MAX_MESSAGE_CHARS = 20_000
        const val MAX_DIFF_CHARS = 8_000
        const val MAX_HISTORY = 120
        const val MAX_HISTORY_CHARS = 16_000
    }
}
