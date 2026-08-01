package io.androllm.feature.chat.export

import android.content.Context
import android.content.Intent
import io.androllm.core.models.Message

object ConversationSharer {

    fun shareText(context: Context, text: String, title: String = "Share Conversation") {
        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, title)
        context.startActivity(shareIntent)
    }

    fun shareConversation(context: Context, conversationTitle: String, messages: List<Message>, format: ExportFormat = ExportFormat.MARKDOWN) {
        val exportedText = ConversationExporter.export(conversationTitle, messages, format)
        shareText(context, exportedText, "Share $conversationTitle")
    }

    fun shareSingleMessage(context: Context, messageText: String) {
        shareText(context, messageText, "Share Message")
    }
}
