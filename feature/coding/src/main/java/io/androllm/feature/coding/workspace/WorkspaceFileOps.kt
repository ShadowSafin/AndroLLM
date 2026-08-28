package io.androllm.feature.coding.workspace

import java.io.File

/** Raised when a file operation would escape the workspace sandbox. */
class WorkspaceSecurityException(message: String) : Exception(message)

/** Raised for ordinary file I/O failures inside the workspace. */
class WorkspaceIoException(message: String) : Exception(message)

/** A single directory entry returned by [WorkspaceFileOps.listDir]. */
data class DirEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long
)

/** A node in the workspace file tree returned by [WorkspaceFileOps.fileTree]. */
data class FileTreeNode(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val children: List<FileTreeNode> = emptyList()
)

/** One grep match. */
data class GrepMatch(
    val relativePath: String,
    val lineNumber: Int,
    val line: String
)

/**
 * Sandboxed file primitives for the active workspace. Every public method
 * resolves its target through [WorkspaceSafety.resolveAny] so a path can never
 * escape the workspace root — a violation raises [WorkspaceSecurityException].
 *
 * This class is deliberately Android-free (pure `java.io`) so the coding tools
 * and their unit tests run on the JVM.
 */
class WorkspaceFileOps(private val root: File) {

    /**
     * Root in the path form used for ALL I/O. Never canonicalized: for
     * workspaces under `/storage/emulated/0/...` the canonical path resolves
     * the /storage symlink to `/data/media/0/...`, which apps cannot open.
     */
    private val ioRoot: File = root.absoluteFile

    /** Canonical root used ONLY for relativizing display paths. */
    private val canonicalRoot: File = root.canonicalFile

    /** Resolves a model-supplied path into the workspace or throws. */
    private fun resolve(path: String): File =
        WorkspaceSafety.resolveAny(ioRoot, path)
            ?: throw WorkspaceSecurityException("Path escapes the workspace: '$path'")

    private fun requireFile(path: String): File {
        val f = resolve(path)
        if (!f.exists()) throw WorkspaceIoException("No such file: '$path'")
        if (!f.isFile) throw WorkspaceIoException("Not a file: '$path'")
        return f
    }

    private fun requireDir(path: String): File {
        val f = resolve(path)
        if (!f.exists()) throw WorkspaceIoException("No such directory: '$path'")
        if (!f.isDirectory) throw WorkspaceIoException("Not a directory: '$path'")
        return f
    }

    private fun rel(target: File): String =
        canonicalRoot.toPath().relativize(target.canonicalFile.toPath()).toString().replace('\\', '/')

    // ── Read / Write ─────────────────────────────────────────────────────────

    fun exists(path: String): Boolean = runCatching { resolve(path).exists() }.getOrDefault(false)

    /** Reads a file as text, capped at [maxChars] to protect the context window. */
    fun readFile(path: String, maxChars: Int = DEFAULT_MAX_READ_CHARS): String {
        val f = requireFile(path)
        val text = f.readText(Charsets.UTF_8)
        return if (text.length > maxChars) text.substring(0, maxChars) + "\n…[truncated ${text.length - maxChars} chars]" else text
    }

    /** Writes [content] to [path], creating parent directories as needed. */
    fun writeFile(path: String, content: String): File {
        val f = resolve(path)
        if (f.exists() && f.isDirectory) throw WorkspaceIoException("Path is a directory: '$path'")
        f.parentFile?.mkdirs()
        f.writeText(content, Charsets.UTF_8)
        return f
    }

    /** Appends [content] to [path] (creates the file when missing). */
    fun appendFile(path: String, content: String): File {
        val f = resolve(path)
        if (f.exists() && f.isDirectory) throw WorkspaceIoException("Path is a directory: '$path'")
        f.parentFile?.mkdirs()
        f.appendText(content, Charsets.UTF_8)
        return f
    }

    /** Result of validating an edit without writing anything. */
    data class EditPreview(
        val originalContent: String,
        val updatedContent: String,
        val replacements: Int
    )

    /**
     * Computes the outcome of an edit WITHOUT writing to disk. Performs the same
     * validation as [editFile] (unique match unless [replaceAll]). Lets tools
     * show a diff preview / run the review gate before anything is applied.
     */
    fun previewEdit(path: String, oldText: String, newText: String, replaceAll: Boolean = false): EditPreview {
        val f = requireFile(path)
        val original = f.readText(Charsets.UTF_8)
        val count = countOccurrences(original, oldText)
        if (count == 0) throw WorkspaceIoException("old_text not found in '$path'")
        if (!replaceAll && count > 1) {
            throw WorkspaceIoException("old_text matches $count times in '$path' — provide more context or set replace_all")
        }
        val updated = if (replaceAll) original.replace(oldText, newText)
        else original.replaceFirst(oldText, newText)
        return EditPreview(original, updated, if (replaceAll) count else 1)
    }

    /**
     * Replaces [oldText] with [newText] in [path]. When [replaceAll] is false the
     * match must be unique; zero or multiple matches raise [WorkspaceIoException]
     * so the agent is forced to add more context (mirrors the edit-tool contract).
     */
    fun editFile(path: String, oldText: String, newText: String, replaceAll: Boolean = false): Int {
        val preview = previewEdit(path, oldText, newText, replaceAll)
        requireFile(path).writeText(preview.updatedContent, Charsets.UTF_8)
        return preview.replacements
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = 0
        while (true) {
            index = haystack.indexOf(needle, index)
            if (index < 0) break
            count++
            index += needle.length
        }
        return count
    }

    /** Deletes a file or (recursively) a directory inside the workspace. */
    fun delete(path: String, recursive: Boolean = false): Boolean {
        val f = resolve(path)
        if (!f.exists()) return false
        if (f.isDirectory && recursive) f.deleteRecursively() else f.delete()
        return true
    }

    // ── Directory inspection ─────────────────────────────────────────────────

    fun listDir(path: String = ""): List<DirEntry> {
        val dir = if (path.isBlank()) ioRoot else requireDir(path)
        val children = dir.listFiles()?.toList().orEmpty()
        return children
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .map { child ->
                DirEntry(
                    name = child.name,
                    relativePath = rel(child),
                    isDirectory = child.isDirectory,
                    sizeBytes = if (child.isFile) child.length() else 0L,
                    lastModified = child.lastModified()
                )
            }
    }

    /**
     * Builds a bounded file tree. Directories are expanded to [maxDepth]; file
     * count per directory is capped at [maxEntries] to keep huge repos small.
     */
    fun fileTree(path: String = "", maxDepth: Int = 3, maxEntries: Int = 200): FileTreeNode {
        val dir = if (path.isBlank()) ioRoot else requireDir(path)
        return buildNode(dir, maxDepth, maxEntries, intArrayOf(0))
    }

    private fun buildNode(dir: File, depth: Int, maxEntries: Int, budget: IntArray): FileTreeNode {
        val children = dir.listFiles()?.toList().orEmpty()
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
        val nodes = mutableListOf<FileTreeNode>()
        for (child in children) {
            if (budget[0] >= maxEntries) {
                nodes += FileTreeNode("… (${children.size - nodes.size} more)", rel(child), isDirectory = false)
                break
            }
            budget[0]++
            if (child.isDirectory && depth > 1) {
                nodes += buildNode(child, depth - 1, maxEntries, budget)
            } else {
                nodes += FileTreeNode(child.name, rel(child), child.isDirectory)
            }
        }
        return FileTreeNode(dir.name.ifBlank { "/" }, rel(dir), isDirectory = true, children = nodes)
    }

    // ── Search ───────────────────────────────────────────────────────────────

    /**
     * Searches file contents for [pattern] (regex). Skips binary files and a
     * small set of noisy directories. [include] is an optional glob applied to
     * file names (e.g. "*.kt"). Returns at most [maxMatches] matches.
     */
    fun grep(
        pattern: String,
        path: String = "",
        include: String? = null,
        maxMatches: Int = 200
    ): List<GrepMatch> {
        val base = if (path.isBlank()) ioRoot else requireDir(path)
        val regex = runCatching { Regex(pattern) }.getOrElse {
            throw WorkspaceIoException("Invalid regex: '${it.message}'")
        }
        val includeRegex = include?.takeIf { it.isNotBlank() }?.let { globToRegex(it) }
        val matches = mutableListOf<GrepMatch>()
        base.walkTopDown()
            .onEnter { dir -> dir.name !in IGNORED_DIRS }
            .filter { it.isFile }
            .filter { includeRegex == null || includeRegex.matches(it.name) }
            .forEach { file ->
                if (matches.size >= maxMatches) return@forEach
                if (looksBinary(file)) return@forEach
                runCatching {
                    file.useLines { lines ->
                        lines.forEachIndexed { idx, line ->
                            if (matches.size >= maxMatches) return@forEachIndexed
                            if (regex.containsMatchIn(line)) {
                                matches += GrepMatch(rel(file), idx + 1, line.take(400))
                            }
                        }
                    }
                }
            }
        return matches
    }

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        for (ch in glob) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> sb.append('\\').append(ch)
                else -> sb.append(ch)
            }
        }
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    private fun looksBinary(file: File): Boolean {
        if (file.length() == 0L) return false
        val head = ByteArray(512)
        val read = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(-1)
        if (read <= 0) return false
        var nulls = 0
        for (i in 0 until read) if (head[i].toInt() == 0) nulls++
        return nulls > 0
    }

    /** Aggregate workspace stats for the summary tool. */
    fun summarize(): WorkspaceSummary {
        var files = 0
        var dirs = 0
        var totalBytes = 0L
        val byExt = HashMap<String, Int>()
        ioRoot.walkTopDown()
            .onEnter { it.name !in IGNORED_DIRS }
            .forEach { f ->
                if (f.isDirectory) dirs++ else {
                    files++
                    totalBytes += f.length()
                    val ext = f.extension.lowercase().ifBlank { "(none)" }
                    byExt[ext] = (byExt[ext] ?: 0) + 1
                }
            }
        val top = byExt.entries.sortedByDescending { it.value }.take(8).associate { it.key to it.value }
        return WorkspaceSummary(
            rootPath = ioRoot.path,
            fileCount = files,
            dirCount = dirs,
            totalBytes = totalBytes,
            filesByExtension = top
        )
    }

    companion object {
        const val DEFAULT_MAX_READ_CHARS = 60_000
        private val IGNORED_DIRS = setOf(
            ".git", "node_modules", "build", "dist", ".gradle", ".idea",
            "__pycache__", ".next", "target", ".cxx", "vendor", "coverage"
        )
    }
}

/** Aggregate stats about a workspace. */
data class WorkspaceSummary(
    val rootPath: String,
    val fileCount: Int,
    val dirCount: Int,
    val totalBytes: Long,
    val filesByExtension: Map<String, Int>
)
