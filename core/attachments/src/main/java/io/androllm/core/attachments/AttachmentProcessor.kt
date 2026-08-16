package io.androllm.core.attachments

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.attachments.model.AttachmentProcessingResult
import io.androllm.core.attachments.model.AttachmentStatus
import io.androllm.core.attachments.model.AttachmentType
import io.androllm.core.attachments.model.ChatAttachment
import io.androllm.core.attachments.parser.ParserRegistry
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Processes picked files into conversation-scoped [ChatAttachment]s.
 *
 * For each URI the processor:
 *  1. copies the content into the conversation cache (a stable private copy
 *     that survives SAF grant expiry),
 *  2. dispatches to the right parser (text formats) or OCR (images),
 *  3. returns the attachment with extracted text ready for prompt assembly.
 *
 * Nothing is indexed or persisted beyond the conversation-scoped cache.
 */
@Singleton
class AttachmentProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: ParserRegistry
) {

    /**
     * Processes a batch of [uris] for [conversationId], invoking [onProgress]
     * after each file. Individual failures never abort the batch — the failed
     * attachment carries its error so the UI can show per-chip status.
     */
    suspend fun processBatch(
        conversationId: String,
        uris: List<Uri>,
        onProgress: (current: Int, total: Int, name: String) -> Unit = { _, _, _ -> }
    ): List<AttachmentProcessingResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<AttachmentProcessingResult>()
        uris.forEachIndexed { index, uri ->
            val name = displayName(uri)
            onProgress(index + 1, uris.size, name)
            results += processOne(conversationId, uri, name)
        }
        results
    }

    /** Processes a single URI. Never throws — failures become [AttachmentStatus.FAILED]. */
    suspend fun processOne(
        conversationId: String,
        uri: Uri,
        name: String = displayName(uri)
    ): AttachmentProcessingResult = withContext(Dispatchers.IO) {
        val attachmentId = UUID.randomUUID().toString()
        val base = AttachmentCache.cacheDir(context, conversationId)
        val safeName = sanitizeName(name)
        val target = File(base, "$attachmentId-$safeName")
        try {
            copyToFile(uri, target)
            if (target.length() <= 0L) throw IllegalArgumentException("File is empty")
            val parser = registry.find(target)
                ?: throw IllegalArgumentException(
                    "Unsupported file type${if (name.isNotBlank()) " \"$name\"" else ""} — " +
                        "attach PDF, Word, PowerPoint, Excel, text, markdown, CSV, JSON, HTML, EPUB or an image"
                )
            val parsed = parser.parse(target)
            val type = parser.format
            AttachmentProcessingResult(
                attachment = ChatAttachment(
                    id = attachmentId,
                    conversationId = conversationId,
                    name = safeName,
                    type = type,
                    mimeType = context.contentResolver.getType(uri).orEmpty(),
                    sourceUri = uri.toString(),
                    filePath = target.absolutePath,
                    sizeBytes = target.length(),
                    text = parsed.text,
                    fromOcr = parsed.fromOcr,
                    pageCount = parsed.pageCount,
                    status = AttachmentStatus.READY,
                    createdAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Timber.w(e, "Attachment processing failed: %s", name)
            // Keep a partial record so the chip shows a retryable failure.
            AttachmentProcessingResult(
                attachment = ChatAttachment(
                    id = attachmentId,
                    conversationId = conversationId,
                    name = safeName,
                    type = AttachmentType.fromFileName(name),
                    mimeType = context.contentResolver.getType(uri).orEmpty(),
                    sourceUri = uri.toString(),
                    filePath = target.absolutePath,
                    sizeBytes = target.length().takeIf { it > 0 } ?: 0,
                    status = AttachmentStatus.FAILED,
                    error = e.message ?: e.javaClass.simpleName,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun copyToFile(uri: Uri, target: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("Could not read the selected file")
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"

    private fun sanitizeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "attachment" }
}
