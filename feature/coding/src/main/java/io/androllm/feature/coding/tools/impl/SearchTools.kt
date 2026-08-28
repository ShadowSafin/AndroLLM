package io.androllm.feature.coding.tools.impl

import io.androllm.feature.coding.tools.CodingTool
import io.androllm.feature.coding.tools.CodingToolContext
import io.androllm.feature.coding.tools.CodingToolResult
import io.androllm.feature.coding.tools.CodingToolSpec
import io.androllm.feature.coding.tools.Schemas
import io.androllm.feature.coding.tools.int
import io.androllm.feature.coding.tools.str
import io.androllm.feature.coding.workspace.WorkspaceIoException
import io.androllm.feature.coding.workspace.WorkspaceSecurityException
import kotlinx.serialization.json.JsonObject

/** Search file contents with a regex (grep). */
class GrepTool : CodingTool {
    override val spec = CodingToolSpec(
        name = "grep",
        description = "Search file contents in the workspace with a regex. Returns matching lines with file paths and line numbers. Optional include glob (e.g. '*.kt').",
        parameters = Schemas.obj(
            mapOf(
                "pattern" to Schemas.string("Regular expression to search for."),
                "path" to Schemas.string("Optional sub-directory to search (default workspace root)."),
                "include" to Schemas.string("Optional filename glob filter, e.g. '*.kt'."),
                "max_matches" to Schemas.integer("Cap on matches returned (default 200).")
            ),
            required = listOf("pattern")
        ),
        readOnly = true
    )

    override suspend fun execute(arguments: JsonObject, context: CodingToolContext): CodingToolResult {
        val pattern = arguments.str("pattern") ?: return CodingToolResult.Failure("Missing 'pattern'.", retryable = false)
        val path = arguments.str("path") ?: ""
        val include = arguments.str("include")
        val max = arguments.int("max_matches", 200)
        return try {
            val matches = context.fileOps.grep(pattern, path, include, max)
            if (matches.isEmpty()) {
                return CodingToolResult.Success("No matches for '$pattern'.")
            }
            val rendered = matches.joinToString("\n") { "${it.relativePath}:${it.lineNumber}: ${it.line}" }
            CodingToolResult.Success("${matches.size} match(es) for '$pattern':\n$rendered")
        } catch (e: WorkspaceSecurityException) {
            CodingToolResult.Failure("Security: ${e.message}", retryable = false)
        } catch (e: WorkspaceIoException) {
            CodingToolResult.Failure(e.message ?: "Grep failed", retryable = true)
        }
    }
}

/** List a directory's entries. */
class ListDirTool : CodingTool {
    override val spec = CodingToolSpec(
        name = "list_dir",
        description = "List files and folders in a workspace directory (ls). Shows type, size and name for each entry.",
        parameters = Schemas.obj(
            mapOf("path" to Schemas.string("Directory to list (default workspace root).")),
            required = emptyList()
        ),
        readOnly = true
    )

    override suspend fun execute(arguments: JsonObject, context: CodingToolContext): CodingToolResult {
        val path = arguments.str("path") ?: ""
        return try {
            val entries = context.fileOps.listDir(path)
            if (entries.isEmpty()) return CodingToolResult.Success("Directory '${path.ifBlank { "." }}' is empty.")
            val rendered = entries.joinToString("\n") { e ->
                val type = if (e.isDirectory) "dir " else "file"
                val size = if (e.isDirectory) "" else "  ${e.sizeBytes}B"
                "$type  ${e.name}$size"
            }
            CodingToolResult.Success("Contents of '${path.ifBlank { "." }}':\n$rendered")
        } catch (e: WorkspaceSecurityException) {
            CodingToolResult.Failure("Security: ${e.message}", retryable = false)
        } catch (e: WorkspaceIoException) {
            CodingToolResult.Failure(e.message ?: "List failed", retryable = true)
        }
    }
}

/** Render a bounded file tree of the workspace. */
class FileTreeTool : CodingTool {
    override val spec = CodingToolSpec(
        name = "file_tree",
        description = "Show the workspace directory tree (bounded depth and entry count). Use to understand project structure quickly.",
        parameters = Schemas.obj(
            mapOf(
                "path" to Schemas.string("Sub-directory to start from (default root)."),
                "max_depth" to Schemas.integer("How deep to expand directories (default 3).")
            ),
            required = emptyList()
        ),
        readOnly = true
    )

    override suspend fun execute(arguments: JsonObject, context: CodingToolContext): CodingToolResult {
        val path = arguments.str("path") ?: ""
        val depth = arguments.int("max_depth", 3)
        return try {
            val tree = context.fileOps.fileTree(path, maxDepth = depth)
            val sb = StringBuilder()
            fun render(node: io.androllm.feature.coding.workspace.FileTreeNode, indent: String, isLast: Boolean) {
                val marker = if (node.isDirectory) "/" else ""
                sb.append(indent).append(if (isLast) "└─ " else "├─ ").append(node.name).append(marker).append('\n')
                val childIndent = indent + if (isLast) "   " else "│  "
                node.children.forEachIndexed { i, c -> render(c, childIndent, i == node.children.lastIndex) }
            }
            sb.append(tree.name).append("/\n")
            tree.children.forEachIndexed { i, c -> render(c, "", i == tree.children.lastIndex) }
            CodingToolResult.Success("Workspace tree:\n$sb")
        } catch (e: WorkspaceSecurityException) {
            CodingToolResult.Failure("Security: ${e.message}", retryable = false)
        } catch (e: WorkspaceIoException) {
            CodingToolResult.Failure(e.message ?: "Tree failed", retryable = true)
        }
    }
}
