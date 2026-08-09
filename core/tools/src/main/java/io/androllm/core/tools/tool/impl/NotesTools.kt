package io.androllm.core.tools.tool.impl

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** App-private notes directory (never leaves the device). */
private fun notesDir(context: Context): File =
    File(context.filesDir, "notes").apply { mkdirs() }

/** Sanitizes a note title into a safe file name (kept readable for the LLM). */
private fun slug(title: String): String =
    title.trim().replace(Regex("[^A-Za-z0-9_ -]"), "").trim()
        .replace(Regex("\\s+"), "_").ifBlank { "note" }

/**
 * Saves a note (creates or overwrites by title). Notes live in app-private
 * storage, so they survive restarts and never require permissions.
 */
@Singleton
class NoteSaveTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "note_save",
        description = "Save a note by title (overwrites an existing note with the same title).",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") { put("type", "string") }
                putJsonObject("content") { put("type", "string") }
            }
            putJsonArray("required") { add("title"); add("content") }
        },
        permission = ToolPermission.NOTES,
        category = ToolCategory.PRODUCTIVITY
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val title = ToolArgs.str(arguments, "title", "name")
            ?: return ToolResult.Failure("Missing required argument: title")
        val content = ToolArgs.str(arguments, "content", "text")
            ?: return ToolResult.Failure("Missing required argument: content")
        val file = File(notesDir(context), "${slug(title)}.md")
        val ok = runCatching { file.writeText(content); true }.getOrDefault(false)
        return if (ok) {
            ToolResult.Success("Note '$title' saved.", buildJsonObject { put("name", slug(title)) })
        } else {
            ToolResult.Failure("Could not write the note.")
        }
    }
}

/** Lists saved note titles. */
@Singleton
class NoteListTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "note_list",
        description = "List the titles of all saved notes.",
        permission = ToolPermission.NOTES,
        category = ToolCategory.PRODUCTIVITY
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val notes = notesDir(context).listFiles { f -> f.extension == "md" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.name.removeSuffix(".md").replace('_', ' ') } ?: emptyList()
        if (notes.isEmpty()) return ToolResult.Success("No notes saved yet.")
        val data = buildJsonObject {
            putJsonArray("notes") { notes.forEach { add(it) } }
        }
        return ToolResult.Success(notes.joinToString("; "), data)
    }
}

/** Reads a saved note's content. */
@Singleton
class NoteGetTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "note_get",
        description = "Read the content of a saved note by title.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") { put("type", "string") }
            }
            putJsonArray("required") { add("title") }
        },
        permission = ToolPermission.NOTES,
        category = ToolCategory.PRODUCTIVITY
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val title = ToolArgs.str(arguments, "title", "name")
            ?: return ToolResult.Failure("Missing required argument: title")
        val file = File(notesDir(context), "${slug(title)}.md")
        if (!file.exists()) return ToolResult.Failure("No note named '$title'.")
        val content = runCatching { file.readText() }.getOrNull()
            ?: return ToolResult.Failure("Could not read the note.")
        return ToolResult.Success(content, buildJsonObject { put("name", slug(title)) })
    }
}

/** Deletes a saved note. */
@Singleton
class NoteDeleteTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "note_delete",
        description = "Delete a saved note by title.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") { put("type", "string") }
            }
            putJsonArray("required") { add("title") }
        },
        permission = ToolPermission.NOTES,
        category = ToolCategory.PRODUCTIVITY,
        requiresConfirmation = true
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val title = ToolArgs.str(arguments, "title", "name")
            ?: return ToolResult.Failure("Missing required argument: title")
        val file = File(notesDir(context), "${slug(title)}.md")
        if (!file.exists()) return ToolResult.Failure("No note named '$title'.")
        val ok = runCatching { file.delete(); true }.getOrDefault(false)
        return if (ok) ToolResult.Success("Deleted note '$title'.") else ToolResult.Failure("Could not delete the note.")
    }
}
