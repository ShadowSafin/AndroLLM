package io.androllm.core.tools.clarification

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Intelligent clarification — asks only for the missing information,
 * not generic "Can you clarify?".
 *
 * Bad:  "Can you clarify?"
 * Good: "Which Dad contact should I message?" / "What should the SMS say?"
 */
@Singleton
class ClarificationEngine @Inject constructor() {

    data class MissingInfo(
        val toolName: String,
        val param: String,
        val question: String
    )

    fun forMissingParam(toolName: String, param: String): MissingInfo {
        val q = when (toolName to param) {
            "send_sms" to "phone" -> "Which contact should I message? (e.g. Dad's phone number or name)"
            "send_sms" to "message" -> "What should the SMS say to $toolName recipient?"
            "make_call" to "phone" -> "Who should I call?"
            "send_email" to "to" -> "What email address should I send to?"
            "send_email" to "subject" -> "What should the email subject be?"
            "send_email" to "body" -> "What should the email body contain?"
            "get_weather" to "location" -> "Which location's weather should I check?"
            "search_web" to "query" -> "What should I search for?"
            "note_save" to "title" -> "What title should the note have?"
            "note_save" to "content" -> "What content should the note contain?"
            "export_pdf" to "content" -> "What content should the PDF contain?"
            "search_places" to "query" -> "What kind of places should I search for?"
            "open_navigation" to "destination" -> "Where should I navigate to?"
            "calendar" to "title" -> "What is the calendar event title?"
            "calendar" to "start" -> "When should the calendar event start?"
            else -> "I need the missing '$param' for tool '$toolName'. Could you provide it?"
        }
        return MissingInfo(toolName, param, q)
    }

    fun aggregate(missing: List<Pair<String, String>>): String {
        if (missing.isEmpty()) return "Could you provide the missing information?"
        if (missing.size == 1) return forMissingParam(missing[0].first, missing[0].second).question
        val parts = missing.map { (tool, param) -> forMissingParam(tool, param).question }
        return parts.joinToString(" Also, ")
    }
}
