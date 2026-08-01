package io.androllm.feature.chat.export

import io.androllm.core.models.Message
import io.androllm.core.models.MessageRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export format options for conversation history.
 */
enum class ExportFormat {
    MARKDOWN,
    PLAIN_TEXT,
    JSON,
    PDF,
    HTML
}

@Serializable
private data class ExportData(
    val title: String,
    val exportedAt: String,
    val messageCount: Int,
    val messages: List<ExportMessage>
)

@Serializable
private data class ExportMessage(
    val role: String,
    val content: String,
    val timestamp: String
)

object ConversationExporter {

    private val json = Json { prettyPrint = true }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun export(
        title: String,
        messages: List<Message>,
        format: ExportFormat
    ): String {
        return when (format) {
            ExportFormat.MARKDOWN -> exportToMarkdown(title, messages)
            ExportFormat.PLAIN_TEXT -> exportToPlainText(title, messages)
            ExportFormat.JSON -> exportToJson(title, messages)
            ExportFormat.PDF -> "# PDF Export Placeholder for $title"
            ExportFormat.HTML -> exportToHtml(title, messages)
        }
    }

    private fun exportToMarkdown(title: String, messages: List<Message>): String = buildString {
        append("# ").append(title).append("\n\n")
        append("*Exported on ").append(dateFormat.format(Date())).append("*\n\n")
        append("---\n\n")

        for (msg in messages) {
            val roleName = when (msg.role) {
                MessageRole.USER -> "**User**"
                MessageRole.ASSISTANT -> "**Assistant**"
                MessageRole.SYSTEM -> "*System*"
            }
            val time = dateFormat.format(Date(msg.timestamp))
            append("### ").append(roleName).append(" <small>(").append(time).append(")</small>\n\n")
            append(msg.content.trim()).append("\n\n")
            append("---\n\n")
        }
    }

    private fun exportToPlainText(title: String, messages: List<Message>): String = buildString {
        append(title.uppercase(Locale.getDefault())).append("\n")
        append("Exported: ").append(dateFormat.format(Date())).append("\n")
        append("========================================\n\n")

        for (msg in messages) {
            val roleName = when (msg.role) {
                MessageRole.USER -> "USER"
                MessageRole.ASSISTANT -> "ASSISTANT"
                MessageRole.SYSTEM -> "SYSTEM"
            }
            val time = dateFormat.format(Date(msg.timestamp))
            append("[").append(time).append("] ").append(roleName).append(":\n")
            append(msg.content.trim()).append("\n\n")
            append("----------------------------------------\n\n")
        }
    }

    private fun exportToJson(title: String, messages: List<Message>): String {
        val exportData = ExportData(
            title = title,
            exportedAt = dateFormat.format(Date()),
            messageCount = messages.size,
            messages = messages.map { msg ->
                ExportMessage(
                    role = msg.role.name.lowercase(),
                    content = msg.content,
                    timestamp = dateFormat.format(Date(msg.timestamp))
                )
            }
        )
        return json.encodeToString(exportData)
    }

    private fun exportToHtml(title: String, messages: List<Message>): String = buildString {
        append("<!DOCTYPE html>\n<html>\n<head>\n")
        append("<meta charset=\"UTF-8\">\n")
        append("<title>").append(title).append("</title>\n")
        append("<style>body{font-family:sans-serif;margin:2rem;background:#121212;color:#e0e0e0;}.msg{margin-bottom:1.5rem;padding:1rem;border-radius:8px;background:#1e1e1e;}.user{border-left:4px solid #6200ee;}.assistant{border-left:4px solid #03dac6;}</style>\n")
        append("</head>\n<body>\n")
        append("<h1>").append(title).append("</h1>\n")
        for (msg in messages) {
            val cls = if (msg.role == MessageRole.USER) "user" else "assistant"
            append("<div class=\"msg ").append(cls).append("\">\n")
            append("<strong>").append(msg.role.name).append("</strong>\n")
            append("<p>").append(msg.content.replace("<", "&lt;").replace(">", "&gt;")).append("</p>\n")
            append("</div>\n")
        }
        append("</body>\n</html>")
    }
}
