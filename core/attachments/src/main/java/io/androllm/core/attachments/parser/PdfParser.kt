package io.androllm.core.attachments.parser

import android.content.Context
import android.graphics.Bitmap
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.androllm.core.attachments.model.AttachmentType
import java.io.File

/**
 * PDF parser: extracts the text layer with PDFBox (pure, fast for digital
 * PDFs). When the text layer is empty (scanned documents) the page images are
 * rendered and OCR'd via [ocr] so scanned PDFs still yield readable text.
 */
class PdfParser(
    context: Context,
    private val ocr: MlKitOcrEngine? = null
) : DocumentParser {

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    override val format = AttachmentType.PDF
    override fun supports(file: File) = file.extension.equals("pdf", true)

    override fun parse(file: File, ocrLanguage: String): ParsedDocument {
        val (text, pages, pageCount) = PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper()
            val pageTexts = mutableListOf<String>()
            for (i in 1..doc.numberOfPages) {
                stripper.startPage = i
                stripper.endPage = i
                val pageText = stripper.getText(doc).trim()
                if (pageText.isNotEmpty()) pageTexts += pageText
            }
            Triple(pageTexts.joinToString("\n\n"), pageTexts, doc.numberOfPages)
        }
        if (text.isNotBlank()) {
            val normalized = TextParsers.normalize(text)
            return ParsedDocument(
                text = normalized,
                pages = pages.map { TextParsers.normalize(it) }.filter { it.isNotEmpty() },
                pageCount = pageCount
            )
        }
        // Scanned PDF: fall back to per-page OCR.
        return ocrPdf(file, ocrLanguage, pageCount)
    }

    private fun ocrPdf(file: File, ocrLanguage: String, pageCount: Int): ParsedDocument {
        val engine = ocr ?: throw IllegalArgumentException("Scanned PDF requires OCR, which is unavailable")
        val pageTexts = mutableListOf<String>()
        for (i in 0 until pageCount) {
            val pageText = engine.ocrPdfPage(file, i)
            if (!pageText.isNullOrBlank()) pageTexts += pageText
        }
        val normalized = TextParsers.normalize(pageTexts.joinToString("\n\n"))
        if (normalized.isEmpty()) throw IllegalArgumentException("PDF contains no readable text")
        return ParsedDocument(
            text = normalized,
            pages = pageTexts.map { TextParsers.normalize(it) }.filter { it.isNotEmpty() },
            pageCount = pageCount,
            fromOcr = true
        )
    }

    /** Renders a page to a bitmap (used by the OCR fallback). */
    fun renderPage(file: File, pageIndex: Int, dpi: Float = 200f): Bitmap? = try {
        PDDocument.load(file).use { doc ->
            PDFRenderer(doc).renderImageWithDPI(pageIndex, dpi)
        }
    } catch (_: Exception) {
        null
    }
}
