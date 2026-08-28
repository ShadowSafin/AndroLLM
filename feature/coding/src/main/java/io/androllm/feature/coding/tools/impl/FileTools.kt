package io.androllm.feature.coding.tools.impl

import io.androllm.feature.coding.tools.ChangeKind
import io.androllm.feature.coding.tools.CodingTool
import io.androllm.feature.coding.tools.CodingToolContext
import io.androllm.feature.coding.tools.CodingToolResult
import io.androllm.feature.coding.tools.CodingToolSpec
import io.androllm.feature.coding.tools.EditReviewThresholds
import io.androllm.feature.coding.tools.PendingFileChange
import io.androllm.feature.coding.tools.Schemas
import io.androllm.feature.coding.tools.bool
import io.androllm.feature.coding.tools.int
import io.androllm.feature.coding.tools.str
import io.androllm.feature.coding.workspace.DiffKind
import io.androllm.feature.coding.workspace.DiffLine
import io.androllm.feature.coding.workspace.DiffStats
import io.androllm.feature.coding.workspace.LineDiff
import io.androllm.feature.coding.workspace.WorkspaceIoException
import io.androllm.feature.coding.workspace.WorkspaceSecurityException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Shared helpers for file-mutating tools: diff data + the major-edit review gate. */
internal object FileChangeSupport {

    /** Reads a file fully enough for diffing; null when missing/too large/truncated. */
    fun readForDiff(context: CodingToolContext, path: String): String? = runCatching {
        val text = context.fileOps.readFile(path, DIFF_READ_CAP)
        if (text.contains(TRUNCATION_MARKER)) null else text
    }.getOrNull()

    /**
     * When a review gate is wired and the change is "major" (per
     * [EditReviewThresholds]), asks the user to approve it BEFORE anything is
     * written. Returns a rejection result, or null when the change may proceed.
     */
    suspend fun reviewIfMajor(
        context: CodingToolContext,
        path: String,
        kind: ChangeKind,
        oldContent: String?,
        newContent: String
    ): CodingToolResult.Failure? {
        val gate = context.editReviewGate ?: return null
        val diffLines = diffLinesFor(oldContent, newContent, created = kind == ChangeKind.CREATE)
        val stats = diffLines?.let { LineDiff.stats(it) }
            ?: DiffStats(added = newContent.lines().size, removed = oldContent?.lines()?.size ?: 0)
        val major = when (kind) {
            ChangeKind.CREATE -> newContent.lines().size > EditReviewThresholds.NEW_FILE_LINES
            ChangeKind.OVERWRITE, ChangeKind.EDIT ->
                stats.added + stats.removed > EditReviewThresholds.EDIT_LINES
        }
        if (!major) return null
        val diffText = diffLines?.let { LineDiff.renderUnified(it) }
            ?: "(file too large for a line diff — full content replacement)"
        val approved = gate.review(
            PendingFileChange(
                path = path,
                kind = kind,
                unifiedDiff = diffText,
                added = stats.added,
                removed = stats.removed
            )
        )
        return if (approved) null else CodingToolResult.Failure(
            "The user REJECTED this change to '$path' — it was NOT applied. " +
                "Ask what they want differently, or propose a smaller/alternative change.",
            retryable = false
        )
    }

    /** Builds the machine-readable change payload the UI renders (diff card). */
    fun changeData(
        path: String,
        oldContent: String?,
        newContent: String,
        created: Boolean
    ): Map<String, kotlinx.serialization.json.JsonElement> {
        val diffLines = diffLinesFor(oldContent, newContent, created)
        val stats = diffLines?.let { LineDiff.stats(it) }
            ?: DiffStats(added = newContent.lines().size, removed = 0)
        val diffText = diffLines?.let { LineDiff.renderUnified(it, maxChars = 4000) }
            ?: if (created) "" else "(no line diff available)"
        return mapOf(
            "path" to JsonPrimitive(path),
            "diff" to JsonPrimitive(diffText),
            "added" to JsonPrimitive(stats.added),
            "removed" to JsonPrimitive(stats.removed),
            "created" to JsonPrimitive(created)
        )
    }

    /**
     * Line diff for a change; created files diff against "nothing" so every line
     * is an addition. Returns null only when the file is too large to diff.
     */
    private fun diffLinesFor(oldContent: String?, newContent: String, created: Boolean): List<DiffLine>? =
        when {
            oldContent != null -> LineDiff.diff(oldContent, newContent)
            created -> newContent.lines().map { DiffLine(DiffKind.ADD, it) }
            else -> null
        }

    /** " (+12 −3)" suffix for change summaries; blank for trivial/unknown diffs. */
    fun statsSuffix(oldContent: String?, newContent: String): String {
        val diffLines = if (oldContent != null) LineDiff.diff(oldContent, newContent) else null
        val stats = diffLines?.let { LineDiff.stats(it) } ?: return ""
        if (stats.added == 0 && stats.removed == 0) return ""
        return " (${stats.render()})"
    }

    private const val DIFF_READ_CAP = 200_000

    // Must match the marker WorkspaceFileOps.readFile appends (U+2026 ellipsis).
    private const val TRUNCATION_MARKER = "\u2026[truncated"
}

/** Read a file from the workspace. */
class ReadFileTool : CodingTool {
    override val spec = CodingToolSpec(
        name = "read_file",
        description = "Read a text file from the workspace and return its contents (with line numbers). Use to inspect source before editing.",
        parameters = Schemas.obj(
            mapOf(
                "path" to Schemas.string("Workspace-relative file path, e.g. 'src/Main.kt'."),
                "max_chars" to Schemas.integer("Optional cap on returned characters (default 60000).")
            ),
            required = listOf("path")
        ),
        readOnly = true
    )

    override suspend fun execute(arguments: JsonObject, context: CodingToolContext): CodingToolResult {
        val path = arguments.str("path") ?: return CodingToolResult.Failure("Missing 'path'.", retryable = false)
        val maxChars = arguments.int("max_chars", 60_000)
        return try {
            val content = context.fileOps.readFile(path, maxChars)
            context.recordFile(path)
            val numbered = content.lines().mapIndexed { i, l -> "${i + 1}: $l" }.joinToString("\n")
            CodingToolResult.Success("Contents of $path:\n$numbered")
        } catch (e: WorkspaceSecurityException) {
            CodingToolResult.Failure("Security: ${e.message}", retryable = false)
        } catch (e: WorkspaceIoException) {
            CodingToolResult.Failure(e.message ?: "Read failed", retryable = true)
        }
    }
}

/** Write (create/overwrite) a file in the workspace. */
class WriteFileTool : CodingTool {
    override val spec = CodingToolSpec(
        name = "write_file",
        description = "Create or overwrite a file in the workspace with the given content. Parent directories are created automatically.",
        parameters = Schemas.obj(
            mapOf(
                "path" to Schemas.string("Workspace-relative file path."),
                "content" to Schemas.string("Full file content to write.")
            ),
            required = listOf("path", "content")
        ),
        requiresConfirmation = false,
        readOnly = false
    )

    override suspend fun execute(arguments: JsonObject, context: CodingToolContext): CodingToolResult {
        val path = arguments.str("path") ?: return CodingToolResult.Failure("Missing 'path'.", retryable = false)
        val content = arguments.str("content") ?: ""
        return try {
            val existed = context.fileOps.exists(path)
            val oldContent = if (existed) FileChangeSupport.readForDiff(context, path) else null

            // Major change → user reviews a diff BEFORE anything is written.
            FileChangeSupport.reviewIfMajor(
                context, path,
                if (existed) ChangeKind.OVERWRITE else ChangeKind.CREATE,
                oldContent, content
            )?.let { return it }

            val f = context.fileOps.writeFile(path, content)
            context.recordFile(path)
            val suffix = FileChangeSupport.statsSuffix(oldContent, content)
            CodingToolResult.Success(
                "Wrote ${content.length} chars to $path (${f.length()} bytes on disk).$suffix",
                data = FileChangeSupport.changeData(path, oldContent, content, created = !existed)
            )
        } catch (e: WorkspaceSecurityException) {
            CodingToolResult.Failure("Security: ${e.message}", retryable = false)
        } catch (e: WorkspaceIoException) {
            CodingToolResult.Failure(e.message ?: "Write failed", retryable = true)
        }
    }
}

/** Edit a file by replacing an exact text fragment (unique match by default). */
class EditFileTool : CodingTool {
    override val spec = CodingToolSpec(
        name = "edit_file",
        description = "Edit a file by replacing an exact text fragment. old_text must match uniquely unless replace_all is true. Use for targeted changes.",
        parameters = Schemas.obj(
            mapOf(
                "path" to Schemas.string("Workspace-relative file path."),
                "old_text" to Schemas.string("Exact existing text to replace (must match)."),
                "new_text" to Schemas.string("Replacement text."),
                "replace_all" to Schemas.boolean("Replace every occurrence (default false).")
            ),
            required = listOf("path", "old_text", "new_text")
        ),
        readOnly = false
    )

    override suspend fun execute(arguments: JsonObject, context: CodingToolContext): CodingToolResult {
        val path = arguments.str("path") ?: return CodingToolResult.Failure("Missing 'path'.", retryable = false)
        val old = arguments.str("old_text") ?: return CodingToolResult.Failure("Missing 'old_text'.", retryable = false)
        val new = arguments.str("new_text") ?: return CodingToolResult.Failure("Missing 'new_text'.", retryable = false)
        val all = arguments.bool("replace_all", false)
        return try {
            // Validate + compute the outcome first (nothing written yet).
            val preview = context.fileOps.previewEdit(path, old, new, all)

            // Major edit → user reviews a diff BEFORE it is applied.
            FileChangeSupport.reviewIfMajor(context, path, ChangeKind.EDIT, preview.originalContent, preview.updatedContent)
                ?.let { return it }

            context.fileOps.writeFile(path, preview.updatedContent)
            context.recordFile(path)
            val suffix = FileChangeSupport.statsSuffix(preview.originalContent, preview.updatedContent)
            CodingToolResult.Success(
                "Edited $path — replaced ${preview.replacements} occurrence${if (preview.replacements == 1) "" else "s"}.$suffix",
                data = FileChangeSupport.changeData(path, preview.originalContent, preview.updatedContent, created = false)
            )
        } catch (e: WorkspaceSecurityException) {
            CodingToolResult.Failure("Security: ${e.message}", retryable = false)
        } catch (e: WorkspaceIoException) {
            CodingToolResult.Failure(e.message ?: "Edit failed", retryable = true)
        }
    }
}

/** Replace text across one file (alias semantics: whole-file string replace). */
class ReplaceTextTool : CodingTool {
    override val spec = CodingToolSpec(
        name = "replace_text",
        description = "Replace every occurrence of a string in a file. Use for renames or repeated tokens; for unique edits prefer edit_file.",
        parameters = Schemas.obj(
            mapOf(
                "path" to Schemas.string("Workspace-relative file path."),
                "find" to Schemas.string("Text to find."),
                "replace" to Schemas.string("Replacement text.")
            ),
            required = listOf("path", "find", "replace")
        ),
        readOnly = false
    )

    override suspend fun execute(arguments: JsonObject, context: CodingToolContext): CodingToolResult {
        val path = arguments.str("path") ?: return CodingToolResult.Failure("Missing 'path'.", retryable = false)
        val find = arguments.str("find") ?: return CodingToolResult.Failure("Missing 'find'.", retryable = false)
        val replace = arguments.str("replace") ?: return CodingToolResult.Failure("Missing 'replace'.", retryable = false)
        return try {
            val preview = context.fileOps.previewEdit(path, find, replace, replaceAll = true)

            FileChangeSupport.reviewIfMajor(context, path, ChangeKind.EDIT, preview.originalContent, preview.updatedContent)
                ?.let { return it }

            context.fileOps.writeFile(path, preview.updatedContent)
            context.recordFile(path)
            val suffix = FileChangeSupport.statsSuffix(preview.originalContent, preview.updatedContent)
            CodingToolResult.Success(
                "Replaced ${preview.replacements} occurrence${if (preview.replacements == 1) "" else "s"} in $path.$suffix",
                data = FileChangeSupport.changeData(path, preview.originalContent, preview.updatedContent, created = false)
            )
        } catch (e: WorkspaceSecurityException) {
            CodingToolResult.Failure("Security: ${e.message}", retryable = false)
        } catch (e: WorkspaceIoException) {
            CodingToolResult.Failure(e.message ?: "Replace failed", retryable = true)
        }
    }
}
