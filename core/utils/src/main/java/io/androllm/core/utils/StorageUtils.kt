package io.androllm.core.utils

import android.content.Context
import io.androllm.core.common.Result
import io.androllm.core.common.runCatching
import java.io.File

/**
 * Helpers for storage inspection and management.
 */
object StorageUtils {

    /**
     * Returns the primary models directory (app-specific external storage).
     * Downloaded .gguf files land here via [io.androllm.feature.models.downloader.DownloadManager].
     */
    fun getModelsDirectory(context: Context): File {
        val base = context.getExternalFilesDir(null)
            ?: context.filesDir
            ?: java.io.File(System.getProperty("java.io.tmpdir"))
        return File(base, "models").apply { mkdirs() }
    }

    /**
     * Returns the secondary models directory (app-private internal storage)
     * where SAF-imported .gguf files land via ModelsViewModel.importModel, or
     * null when the app-private files dir is unavailable (tests / unusual
     * environments) — in which case there is no separate import directory to
     * count.
     */
    fun getImportedModelsDirectory(context: Context): File? =
        context.filesDir?.let { File(it, "models").apply { mkdirs() } }

    /**
     * Returns storage stats for the models directories in bytes.
     *
     * - [StorageStats.totalBytes] — total space of the filesystem that hosts the
     *   primary models directory.
     * - [StorageStats.usedBytes] — bytes occupied by model files in BOTH the
     *   download directory and the SAF-import directory (so "Models on Device"
     *   is never 0 when models exist).
     * - [StorageStats.freeBytes] — the REAL free space still available on that
     *   filesystem (the old implementation derived "available" as
     *   `total - modelsUsed`, which ignored every other app on the device and
     *   reported wildly optimistic free space).
     */
    fun getStorageStats(context: Context): StorageStats {
        val modelsDir = getModelsDirectory(context)
        val total = modelsDir.totalSpace
        val free = modelsDir.freeSpace
        val used = calculateDirectorySize(modelsDir) +
            (getImportedModelsDirectory(context)?.let { calculateDirectorySize(it) } ?: 0L)
        return StorageStats(totalBytes = total, usedBytes = used, freeBytes = free)
    }

    /**
     * Calculates the recursive size of a directory in bytes.
     */
    fun calculateDirectorySize(directory: File): Long {
        if (!directory.exists()) return 0
        return directory.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Clears the app cache directory.
     */
    suspend fun clearCache(context: Context): Result<Long> = io.androllm.core.common.runCatching {
        val cacheDir = context.cacheDir
        val size = calculateDirectorySize(cacheDir)
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
        size
    }

    /**
     * Returns the human readable size of the given byte count.
     */
    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(java.util.Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}

/**
 * Storage usage summary.
 *
 * [totalBytes]/[usedBytes] describe the models directory on its host filesystem;
 * [freeBytes] is the real free space still available for new model downloads
 * (and equals [availableBytes]).
 */
data class StorageStats(
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long = 0L
) {
    /** Real free space on the filesystem hosting the models directory. */
    val availableBytes: Long get() = if (freeBytes > 0) freeBytes else (totalBytes - usedBytes).coerceAtLeast(0L)

    /** Fraction of the filesystem still free (0f..1f), for usage bars. */
    val freeFraction: Float get() = if (totalBytes > 0) availableBytes.toFloat() / totalBytes else 0f
}
