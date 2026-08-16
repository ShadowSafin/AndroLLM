package io.androllm.core.attachments

import android.content.Context
import java.io.File

/**
 * Conversation-scoped attachment cache. Each conversation owns a directory
 * under `filesDir/attachments/{conversationId}/` holding its private file
 * copies. Deleting a conversation removes its directory; the whole cache is
 * cleared by the "Clear temporary attachment cache" setting. Nothing here is
 * indexed or shared across conversations.
 */
object AttachmentCache {

    private const val ROOT = "chat_attachments"

    /** The cache directory for [conversationId] (created on demand). */
    fun cacheDir(context: Context, conversationId: String): File {
        val dir = File(context.filesDir, "$ROOT/$conversationId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Removes every cached file of one conversation (called on conversation delete). */
    fun clearConversation(context: Context, conversationId: String) {
        if (conversationId.isBlank()) return
        File(context.filesDir, "$ROOT/$conversationId").deleteRecursively()
    }

    /** Removes the entire attachment cache (user-triggered cleanup). */
    fun clearAll(context: Context) {
        File(context.filesDir, ROOT).deleteRecursively()
    }

    /** Total bytes currently cached (for the settings "storage used" row). */
    fun totalBytes(context: Context): Long {
        val root = File(context.filesDir, ROOT)
        if (!root.exists()) return 0L
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
