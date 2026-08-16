package io.androllm.core.attachments.model

import kotlinx.serialization.Serializable

/**
 * Attachment file types the chat can ingest. Future-proofed: AUDIO and VIDEO
 * are reserved so multimodal chat can be added without schema changes.
 */
enum class AttachmentType(val label: String) {
    PDF("PDF"),
    DOCX("Word"),
    PPTX("PowerPoint"),
    XLSX("Excel"),
    EPUB("EPUB"),
    TXT("Text"),
    MARKDOWN("Markdown"),
    CSV("CSV"),
    JSON("JSON"),
    HTML("HTML"),
    IMAGE("Image"),
    /** Reserved for future audio attachment support. */
    AUDIO("Audio"),
    /** Reserved for future video attachment support. */
    VIDEO("Video"),
    UNKNOWN("Unknown");

    val isText: Boolean get() = this in setOf(TXT, MARKDOWN, CSV, JSON, HTML)
    val isImage: Boolean get() = this == IMAGE

    companion object {
        fun fromFileName(name: String): AttachmentType {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "pdf" -> PDF
                "docx" -> DOCX
                "pptx" -> PPTX
                "xlsx" -> XLSX
                "epub" -> EPUB
                "txt", "text", "log" -> TXT
                "md", "markdown" -> MARKDOWN
                "csv" -> CSV
                "json" -> JSON
                "html", "htm" -> HTML
                "png", "jpg", "jpeg", "heic", "webp", "bmp", "gif" -> IMAGE
                "mp3", "wav", "m4a", "ogg", "aac", "flac" -> AUDIO
                "mp4", "mkv", "webm", "mov", "avi", "3gp" -> VIDEO
                else -> UNKNOWN
            }
        }
    }
}

/** Lifecycle of one attached file within its conversation. */
enum class AttachmentStatus {
    PENDING,     // selected, not yet processed
    PROCESSING,  // copying / parsing / OCR in flight
    READY,       // processed; text extracted (or native image ready)
    FAILED       // processing failed (error holds the reason)
}

/**
 * A file attached to a single conversation. Scoped to [conversationId] —
 * nothing is indexed, embedded or shared across chats. The extracted text
 * ([text]) is only ever sent to the model for THIS conversation; it is held
 * in the conversation-scoped cache and dropped when the conversation (or the
 * app cache) is cleared.
 */
@Serializable
data class ChatAttachment(
    val id: String,
    val conversationId: String,
    val name: String,
    val type: AttachmentType,
    val mimeType: String = "",
    /** Original content URI ("" when copied from a raw path). */
    val sourceUri: String = "",
    /** Absolute path of the conversation-scoped private copy. */
    val filePath: String = "",
    val sizeBytes: Long = 0,
    /** Extracted text for text documents / OCR for images. */
    val text: String = "",
    /** True when [text] came from OCR (images / scanned PDFs). */
    val fromOcr: Boolean = false,
    /** Number of text pages/chunks (0 when not applicable). */
    val pageCount: Int = 0,
    val status: AttachmentStatus = AttachmentStatus.PENDING,
    val error: String? = null,
    val createdAt: Long = 0
) {
    val isReady: Boolean get() = status == AttachmentStatus.READY
    val isFailed: Boolean get() = status == AttachmentStatus.FAILED

    /** Human label: "ProjectProposal.pdf · 1.2 MB". */
    val label: String
        get() = buildString {
            append(name.ifBlank { "Attachment" })
            if (sizeBytes > 0) append(" · ").append(formatSize(sizeBytes))
        }

    companion object {
        fun formatSize(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}

/**
 * Attachment behavior settings. Unlike a document index, these only govern
 * how a picked file is processed for the CURRENT conversation.
 */
data class AttachmentSettings(
    /** Image processing quality 0-100 (used when auto-compressing before upload). */
    val imageQuality: Int = 85,
    /** OCR language hint passed to the recognizer. */
    val ocrLanguage: String = "en",
    /** Maximum single-file size in bytes; larger files fail gracefully. */
    val maxAttachmentBytes: Long = 20L * 1024 * 1024,
    /** Maximum attachments per message. */
    val maxAttachmentsPerMessage: Int = 10,
    /** Auto-compress images (decode + re-encode at [imageQuality]) before sending. */
    val autoCompressImages: Boolean = true,
    /** Keep the original filename for copied attachments. */
    val preserveFilenames: Boolean = true,
    /** Cache processed text for the active conversation only. */
    val cacheProcessedAttachments: Boolean = true
) {
    companion object {
        const val IMAGE_QUALITY_MIN = 30
        const val IMAGE_QUALITY_MAX = 100
        const val MAX_ATTACHMENTS_MIN = 1
        const val MAX_ATTACHMENTS_MAX = 20
    }
}

/** Result of processing one attachment. */
data class AttachmentProcessingResult(
    val attachment: ChatAttachment,
    val error: String? = null
)

/** Aggregate progress for a multi-file attach operation. */
data class AttachmentBatchProgress(
    val current: Int = 0,
    val total: Int = 0,
    val currentName: String = ""
) {
    val fraction: Float get() = if (total <= 0) 0f else (current.toFloat() / total).coerceIn(0f, 1f)
}
