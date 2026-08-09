package io.androllm.core.tools.tool.impl

import android.content.Context
import android.graphics.Color
import android.graphics.pdf.PdfDocument
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
 * Generates a paginated PDF from plain text using Android's built-in
 * [PdfDocument] — no third-party library, works fully offline. The file is
 * written to the app's Downloads-adjacent exports dir and its path returned
 * (the Share tool can offer it to other apps afterwards).
 */
@Singleton
class PdfExportTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "export_pdf",
        description = "Save text content as a PDF file and return its path.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("content") { put("type", "string"); put("description", "The text to put in the PDF" ) }
                putJsonObject("title") { put("type", "string"); put("description", "Optional document title" ) }
            }
            putJsonArray("required") { add("content") }
        },
        permission = ToolPermission.FILES,
        category = ToolCategory.PRODUCTIVITY
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val content = ToolArgs.str(arguments, "content", "text", "markdown")
            ?: return ToolResult.Failure("Missing required argument: content")
        val title = ToolArgs.str(arguments, "title", "filename") ?: "document"
        val file = exportFile(title)
        val ok = runCatching {
            writePdf(file, title, content)
            file.exists() && file.length() > 0
        }.getOrDefault(false)
        if (!ok) return ToolResult.Failure("Could not write the PDF file.")
        return ToolResult.Success(
            "PDF saved (${file.length() / 1024} KB): ${file.absolutePath}",
            buildJsonObject { put("path", file.absolutePath); put("bytes", file.length()) }
        )
    }

    private fun exportFile(title: String): File {
        val dir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val safe = title.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_').ifBlank { "document" }
        return File(dir, "${safe}_${System.currentTimeMillis()}.pdf")
    }

    private fun writePdf(file: File, title: String, content: String) {
        val pageWidth = 595
        val pageHeight = 842 // A4 @ 72dpi
        val margin = 48
        val lineHeight = 16
        val paint = android.graphics.Paint().apply {
            color = Color.BLACK
            textSize = 12f
        }
        val titlePaint = android.graphics.Paint().apply {
            color = Color.BLACK
            textSize = 18f
            isFakeBoldText = true
        }
        val doc = PdfDocument()
        try {
            val lines = content.lines().flatMap { wrap(it, pageWidth - margin * 2, paint) }
            var page: PdfDocument.Page? = null
            var y = 0
            fun ensurePage(): PdfDocument.Page {
                if (page == null || y > pageHeight - margin - lineHeight) {
                    page?.let { doc.finishPage(it) }
                    val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, doc.pages.size + 1).create()
                    val newPage = doc.startPage(info)
                    val canvas = newPage.canvas
                    canvas.drawText(title, margin.toFloat(), 72f, titlePaint)
                    y = 96
                    page = newPage
                }
                return page!!
            }
            lines.forEach { line ->
                val p = ensurePage()
                p.canvas.drawText(line, margin.toFloat(), y.toFloat(), paint)
                y += lineHeight
            }
            page?.let { doc.finishPage(it) }
            file.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    private fun wrap(text: String, maxWidth: Int, paint: android.graphics.Paint): List<String> {
        if (text.isBlank()) return listOf(" ")
        val words = text.split(Regex("(?<=\\s)|(?=\\s)"))
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current$word"
            if (paint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                current.append(word)
            } else {
                lines += current.toString()
                current.setLength(0)
                current.append(word)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }
}

/**
 * Saves content as a Markdown (.md) file and returns its path — the raw
 * material behind "save the summary as a PDF / export this as Markdown".
 */
@Singleton
class MarkdownExportTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "export_markdown",
        description = "Save text content as a Markdown (.md) file and return its path.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("content") { put("type", "string") }
                putJsonObject("filename") { put("type", "string") }
            }
            putJsonArray("required") { add("content") }
        },
        permission = ToolPermission.FILES,
        category = ToolCategory.PRODUCTIVITY
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val content = ToolArgs.str(arguments, "content", "text", "markdown")
            ?: return ToolResult.Failure("Missing required argument: content")
        val filename = ToolArgs.str(arguments, "filename", "title") ?: "document"
        val dir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val safe = filename.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_').ifBlank { "document" }
        val file = File(dir, "${safe}_${System.currentTimeMillis()}.md")
        val ok = runCatching {
            file.writeText(content)
            file.exists() && file.length() > 0
        }.getOrDefault(false)
        if (!ok) return ToolResult.Failure("Could not write the Markdown file.")
        return ToolResult.Success(
            "Markdown saved (${file.length() / 1024} KB): ${file.absolutePath}",
            buildJsonObject { put("path", file.absolutePath); put("bytes", file.length()) }
        )
    }
}
