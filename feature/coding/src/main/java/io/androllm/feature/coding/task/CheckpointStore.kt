package io.androllm.feature.coding.task

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One snapshot of a workspace at a point in time: a list of files by their
 * workspace-relative path, with the captured bytes. Kept serializable so the
 * on-disk layout is straightforward and JVM-testable.
 */
@Serializable
data class CheckpointSnapshot(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val files: List<CheckpointFile> = emptyList()
)

@Serializable
data class CheckpointFile(
    /** Workspace-relative path (POSIX style, forward slashes). */
    val path: String,
    /** True for empty files; otherwise the captured content. */
    val empty: Boolean = false,
    /** Base64-encoded bytes — JSON-safe for any content including binary. */
    val contentBase64: String = ""
)

/**
 * Persistence boundary for workspace checkpoints. Production stores each
 * checkpoint as a directory under the app's checkpoints root; unit tests use
 * a temp directory. All operations are suspend so the I/O is non-blocking on
 * Android.
 */
interface CheckpointStore {
    /** Persists a snapshot of [files] (workspace-relative paths → bytes) under [name]. */
    suspend fun create(name: String, files: List<Pair<String, ByteArray>>): CheckpointRef
    /** Restores a checkpoint: writes its files into [intoDir]. Skips paths that escape the dir. */
    suspend fun restore(checkpointId: String, intoDir: File): Int
    /** Lists the most recent checkpoints (newest first). */
    suspend fun list(): List<CheckpointRef>
    /** Removes a single checkpoint. */
    suspend fun delete(checkpointId: String): Boolean
    /** Removes every checkpoint. */
    suspend fun clear()
    /** Captures the current contents of [dir] and returns a snapshot ready to persist. */
    suspend fun snapshot(dir: File, maxFileBytes: Long = MAX_FILE_BYTES): List<Pair<String, ByteArray>>
}

/** Hard upper bound on a single file in a snapshot — keeps the JSON manageable. */
const val MAX_FILE_BYTES: Long = 1_500_000L  // 1.5 MB

/**
 * File-backed [CheckpointStore] using a directory layout:
 *   {root}/<id>/meta.json
 *   {root}/<id>/files/<workspace-relative-path>
 * Skips ignored directories (build artifacts, VCS metadata) and oversize
 * files. Restore only overwrites files that are part of the snapshot.
 */
class FileCheckpointStore(private val root: File) : CheckpointStore {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    override suspend fun create(name: String, files: List<Pair<String, ByteArray>>): CheckpointRef =
        withContext(Dispatchers.IO) {
            root.mkdirs()
            val id = java.util.UUID.randomUUID().toString()
            val dir = File(root, id).apply { mkdirs() }
            val filesDir = File(dir, "files").apply { mkdirs() }
            var totalSize = 0L
            for ((rel, bytes) in files) {
                if (rel.isBlank() || rel.contains("..")) continue
                val target = File(filesDir, rel).apply { parentFile?.mkdirs() }
                target.writeBytes(bytes)
                totalSize += bytes.size
            }
            val meta = CheckpointSnapshot(
                id = id,
                name = name.ifBlank { "Checkpoint ${java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US).format(java.util.Date())}" },
                createdAtMs = System.currentTimeMillis(),
                files = emptyList()
            )
            File(dir, "meta.json").writeText(json.encodeToString(meta))
            CheckpointRef(id = id, name = meta.name, createdAtMs = meta.createdAtMs, fileCount = files.size, sizeBytes = totalSize)
        }

    override suspend fun restore(checkpointId: String, intoDir: File): Int = withContext(Dispatchers.IO) {
        val dir = File(root, checkpointId)
        if (!dir.isDirectory) return@withContext 0
        val filesDir = File(dir, "files")
        if (!filesDir.isDirectory) return@withContext 0
        intoDir.mkdirs()
        val intoCanonical = intoDir.canonicalFile
        var written = 0
        filesDir.walkTopDown().forEach { f ->
            if (f.isFile) {
                val rel = f.relativeTo(filesDir).invariantSeparatorsPath
                val target = File(intoDir, rel)
                val resolved = target.canonicalFile
                // Containment: never write outside the destination.
                if (resolved.absolutePath == intoCanonical.absolutePath ||
                    resolved.absolutePath.startsWith(intoCanonical.absolutePath + File.separator)
                ) {
                    target.parentFile?.mkdirs()
                    target.writeBytes(f.readBytes())
                    written++
                }
            }
        }
        written
    }

    override suspend fun list(): List<CheckpointRef> = withContext(Dispatchers.IO) {
        if (!root.exists()) return@withContext emptyList()
        root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir -> readMeta(dir) }
            ?.sortedByDescending { it.createdAtMs }
            .orEmpty()
    }

    override suspend fun delete(checkpointId: String): Boolean = withContext(Dispatchers.IO) {
        val dir = File(root, checkpointId)
        dir.exists() && dir.deleteRecursively()
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        if (root.exists()) root.deleteRecursively()
        root.mkdirs()
        Unit
    }

    override suspend fun snapshot(dir: File, maxFileBytes: Long): List<Pair<String, ByteArray>> =
        withContext(Dispatchers.IO) {
            if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()
            val base = dir.canonicalFile
            val result = mutableListOf<Pair<String, ByteArray>>()
            base.walkTopDown()
                .onEnter { f -> f.name !in IGNORED_DIRS }
                .forEach { f ->
                    if (!f.isFile) return@forEach
                    if (f.length() > maxFileBytes) return@forEach
                    val rel = f.canonicalFile.relativeTo(base).invariantSeparatorsPath
                    if (rel.startsWith("..") || rel.contains("..")) return@forEach
                    result.add(rel to f.readBytes())
                }
            result
        }

    private fun readMeta(dir: File): CheckpointRef? {
        val metaFile = File(dir, "meta.json")
        if (!metaFile.exists()) return null
        val meta = runCatching { json.decodeFromString<CheckpointSnapshot>(metaFile.readText()) }.getOrNull()
            ?: return null
        val filesDir = File(dir, "files")
        val fileCount = if (filesDir.isDirectory) filesDir.walkTopDown().count { it.isFile } else 0
        val size = if (filesDir.isDirectory) filesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0
        return CheckpointRef(id = meta.id, name = meta.name, createdAtMs = meta.createdAtMs, fileCount = fileCount, sizeBytes = size)
    }

    private companion object {
        val IGNORED_DIRS = setOf(
            ".git", "node_modules", "build", "dist", ".gradle", ".idea",
            "__pycache__", ".next", "target", ".cxx", "vendor", "coverage",
            ".androllm" // never checkpoint our own state
        )
    }
}
