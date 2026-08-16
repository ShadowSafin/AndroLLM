package io.androllm.feature.chat

import io.androllm.core.attachments.model.ChatAttachment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * JSON codec for [ChatAttachment] lists. Attachments travel through the
 * Message model as a JSON string (attachmentsJson) so the chat feature owns
 * the attachment-domain type while core/models stays dependency-free.
 */
object ChatAttachmentJson {

    private val json = Json { ignoreUnknownKeys = true }

    fun encodeToString(attachments: List<ChatAttachment>): String =
        json.encodeToString(attachments)

    fun decodeFromString(jsonText: String): List<ChatAttachment> =
        if (jsonText.isBlank()) emptyList()
        else runCatching { json.decodeFromString<List<ChatAttachment>>(jsonText) }.getOrDefault(emptyList())
}
