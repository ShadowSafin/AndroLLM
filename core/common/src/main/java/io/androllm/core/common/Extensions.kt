package io.androllm.core.common

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Extension functions for Context.
 */
fun Context.getAppName(): String = packageManager.getApplicationLabel(applicationInfo).toString()

fun Context.getVersionName(): String? = packageManager.getPackageInfo(packageName, 0).versionName

fun Context.getVersionCode(): Int = packageManager.getPackageInfo(packageName, 0).versionCode

fun Context.getCacheDirPath(): String = cacheDir.absolutePath

fun Context.getFilesDirPath(): String = filesDir.absolutePath

fun Context.getExternalFilesDirPath(type: String? = null): String? = getExternalFilesDir(type)?.absolutePath

fun Context.isExternalStorageAvailable(): Boolean = android.os.Environment.getExternalStorageState() == android.os.Environment.MEDIA_MOUNTED

fun Context.getFileProviderUri(file: File): Uri = FileProvider.getUriForFile(
    this,
    "${packageName}.fileprovider",
    file
)

/**
 * Extension functions for String.
 */
fun String.toFile(): File = File(this)

fun String.toUri(): Uri = Uri.parse(this)

fun String.capitalizeFirst(): String = if (isNotEmpty()) this[0].uppercase() + substring(1) else this

/**
 * Extension functions for File.
 */
fun File.getSizeInMB(): Double = length() / (1024.0 * 1024.0)

fun File.getSizeInGB(): Double = length() / (1024.0 * 1024.0 * 1024.0)

fun File.getHumanReadableSize(): String {
    return when {
        length() < 1024 -> "${length()} B"
        length() < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", length() / 1024.0)
        length() < 1024 * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", length() / (1024.0 * 1024.0))
        else -> String.format(Locale.getDefault(), "%.1f GB", length() / (1024.0 * 1024.0 * 1024.0))
    }
}

fun File.isModelFile(): Boolean = extension.lowercase() in setOf("gguf", "bin", "ggml", "safetensors", "pt", "pth")

/**
 * Extension functions for Long (file sizes, timestamps).
 */
fun Long.formatFileSize(): String {
    return when {
        this < 1024 -> "$this B"
        this < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", this / 1024.0)
        this < 1024 * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", this / (1024.0 * 1024.0))
        else -> String.format(Locale.getDefault(), "%.1f GB", this / (1024.0 * 1024.0 * 1024.0))
    }
}

fun Long.formatTimestamp(): String {
    return SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(this)
}

fun Long.formatRelativeTime(): String {
    val now = System.currentTimeMillis()
    val diff = now - this
    return when {
        diff < 60_000 -> "Just now"
        diff < 3_600_000 -> "${diff / 60_000} min ago"
        diff < 86_400_000 -> "${diff / 3_600_000} hours ago"
        diff < 604_800_000 -> "${diff / 86_400_000} days ago"
        else -> formatTimestamp()
    }
}

/**
 * Extension functions for Throwable.
 */
fun Throwable.getRootCause(): Throwable {
    var cause: Throwable? = this
    while (cause?.cause != null && cause.cause != cause) {
        cause = cause.cause
    }
    return cause ?: this
}

fun Throwable.getUserMessage(): String {
    return when (this) {
        is java.io.IOException -> "Network error. Please check your connection."
        is java.net.SocketTimeoutException -> "Connection timed out. Please try again."
        is java.net.UnknownHostException -> "Unable to reach server. Check your internet connection."
        is SecurityException -> "Permission denied. Please grant required permissions."
        is IllegalArgumentException -> "Invalid input. Please check your data."
        else -> localizedMessage ?: "An unexpected error occurred"
    }
}

/**
 * Extension functions for List.
 */
fun <T> List<T>.isNullOrEmpty(): Boolean = this.isNullOrEmpty()

fun <T> List<T>.firstOrNullSafe(): T? = if (isNotEmpty()) first() else null

fun <T> List<T>.lastOrNullSafe(): T? = if (isNotEmpty()) last() else null
