package io.androllm.core.tools.tool.impl

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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

/**
 * Lists recent files in the shared Downloads folder via MediaStore
 * (Android 10+ scoped storage — no permission needed for the public
 * Downloads collection). Older APIs fall back to the public directory.
 */
@Singleton
class ListDownloadsTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "list_downloads",
        description = "List recent files in the Downloads folder (name, size, when downloaded).",
        permission = ToolPermission.FILES,
        category = ToolCategory.PRODUCTIVITY
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val files = if (Build.VERSION.SDK_INT >= 29) {
            queryMediaStore()
        } else {
            legacyPublicDownloads()
        }
        if (files.isEmpty()) return ToolResult.Success("No recent downloads found.")
        val data = buildJsonObject {
            putJsonArray("files") {
                files.forEach { f ->
                    add(buildJsonObject {
                        put("name", f.name)
                        put("size_kb", f.bytes / 1024)
                        put("modified", f.modifiedAt)
                    })
                }
            }
        }
        return ToolResult.Success(
            files.joinToString("; ", limit = 15, truncated = "…") { "${it.name} (${it.bytes / 1024} KB)" },
            data
        )
    }

    private fun queryMediaStore(): List<FileEntry> = runCatching {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED
        )
        val entries = mutableListOf<FileEntry>()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection, null, null, "${MediaStore.Downloads.DATE_MODIFIED} DESC LIMIT 25"
        )?.use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                entries += FileEntry(
                    name = cursor.getString(nameCol) ?: "file",
                    bytes = cursor.getLong(sizeCol),
                    modifiedAt = cursor.getLong(modCol) * 1000L
                )
            }
        }
        entries
    }.getOrDefault(emptyList())

    @Suppress("DEPRECATION")
    private fun legacyPublicDownloads(): List<FileEntry> = runCatching {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .listFiles()?.sortedByDescending { it.lastModified() }
            ?.take(25)
            ?.map { FileEntry(it.name, it.length(), it.lastModified()) }
            ?: emptyList()
    }.getOrDefault(emptyList())

    private data class FileEntry(val name: String, val bytes: Long, val modifiedAt: Long)
}

/**
 * Lists the app's own export/notes/recordings files — the artifacts the
 * export tools produce ("where did my PDF go?").
 */
@Singleton
class ListAppFilesTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "list_app_files",
        description = "List files this assistant has created on the device (exports, notes, recordings) with their paths.",
        permission = ToolPermission.FILES,
        category = ToolCategory.PRODUCTIVITY
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val entries = mutableListOf<Pair<String, Long>>()
        val exports = File(context.getExternalFilesDir(null), "exports")
        if (exports.isDirectory) {
            exports.listFiles()?.sortedByDescending { it.lastModified() }?.forEach {
                entries += it.absolutePath to it.length()
            }
        }
        val notes = File(context.filesDir, "notes")
        if (notes.isDirectory) {
            notes.listFiles()?.sortedByDescending { it.lastModified() }?.forEach {
                entries += it.absolutePath to it.length()
            }
        }
        val recordings = File(context.filesDir, "recordings")
        if (recordings.isDirectory) {
            recordings.listFiles()?.sortedByDescending { it.lastModified() }?.forEach {
                entries += it.absolutePath to it.length()
            }
        }
        if (entries.isEmpty()) return ToolResult.Success("No files created by the assistant yet.")
        val data = buildJsonObject {
            putJsonArray("files") {
                entries.forEach { (path, bytes) ->
                    add(buildJsonObject { put("path", path); put("size_kb", bytes / 1024) })
                }
            }
        }
        return ToolResult.Success(
            entries.joinToString("; ", limit = 20, truncated = "…") { (path, _) -> path.substringAfterLast('/') },
            data
        )
    }
}
