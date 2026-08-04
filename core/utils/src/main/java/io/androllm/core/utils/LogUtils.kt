package io.androllm.core.utils

import android.content.Context
import android.os.Environment
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Helpers for capturing and reading app logs.
 */
object LogUtils {
    private const val LOG_DIR_NAME = "logs"
    private const val LOG_FILE_NAME = "androllm.log"
    private const val MAX_LOG_SIZE_BYTES = 5L * 1024L * 1024L
    private const val MAX_LOG_LINES_AFTER_ROTATION = 2000

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun getLogDirectory(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        return File(base, LOG_DIR_NAME).apply { if (!exists()) mkdirs() }
    }

    fun getLogFile(context: Context): File {
        val logDir = getLogDirectory(context)
        return File(logDir, LOG_FILE_NAME)
    }

    fun appendLogLine(context: Context, priority: Int, tag: String?, message: String, throwable: Throwable? = null) {
        val file = getLogFile(context)
        rotateIfNeeded(file)

        val level = when (priority) {
            android.util.Log.VERBOSE -> "V"
            android.util.Log.DEBUG -> "D"
            android.util.Log.INFO -> "I"
            android.util.Log.WARN -> "W"
            android.util.Log.ERROR -> "E"
            android.util.Log.ASSERT -> "A"
            else -> "U"
        }
        val time = timestampFormat.format(Date())
        val tagPart = tag ?: "AndroLLM"
        val line = StringBuilder().apply {
            append(time)
            append(" ")
            append(level)
            append("/")
            append(tagPart)
            append(": ")
            append(message)
            if (throwable != null) {
                append("\n")
                append(android.util.Log.getStackTraceString(throwable))
            }
        }.toString()

        try {
            BufferedWriter(FileWriter(file, true)).use { writer ->
                writer.append(line)
                writer.newLine()
            }
        } catch (_: IOException) {
            // Best-effort only; do not crash the app when logging fails.
        }
    }

    fun readRecentLogs(context: Context, maxLines: Int = 250): String {
        val file = getLogFile(context)
        if (!file.exists()) return ""

        return try {
            val lines = file.useLines { it.toList() }
            lines.takeLast(maxLines).joinToString("\n")
        } catch (_: IOException) {
            ""
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() <= MAX_LOG_SIZE_BYTES) return

        val lines = try {
            file.useLines { it.toList() }
        } catch (_: IOException) {
            return
        }

        val truncated = lines.takeLast(MAX_LOG_LINES_AFTER_ROTATION)
        try {
            file.writeText(truncated.joinToString("\n") + "\n")
        } catch (_: IOException) {
            // ignore rotation failure
        }
    }
}
