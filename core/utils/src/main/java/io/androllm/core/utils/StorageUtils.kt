package io.androllm.core.utils

import android.content.Context
import android.os.Environment
import io.androllm.core.common.Result
import io.androllm.core.common.runCatching
import java.io.File

/**
 * Helpers for storage inspection and management.
 */
object StorageUtils {

    /**
     * Returns the default models directory (app-specific external storage).
     */
    fun getModelsDirectory(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        return File(base, "models").apply { mkdirs() }
    }

    /**
     * Returns the total and used space of the app-specific storage in bytes.
     */
    fun getStorageStats(context: Context): StorageStats {
        val modelsDir = getModelsDirectory(context)
        val total = modelsDir.totalSpace
        val used = calculateDirectorySize(modelsDir)
        return StorageStats(totalBytes = total, usedBytes = used)
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
 */
data class StorageStats(
    val totalBytes: Long,
    val usedBytes: Long
) {
    val availableBytes: Long get() = totalBytes - usedBytes
}
