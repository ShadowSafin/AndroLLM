package io.androllm.core.attachments.parser

import io.androllm.core.attachments.model.AttachmentType
import java.io.File

/**
 * The result of extracting text from a document. Keeps the per-page split so
 * the prompt assembler can attach page numbers, plus the heading outline when
 * the format provides one (markdown `#`, HTML `<h1..h6>`, PDF bookmarks).
 */
data class ParsedDocument(
    /** Full normalized text (single string, whitespace-collapsed). */
    val text: String,
    /** Per-page text split (page 0 when the format has no pages). */
    val pages: List<String> = emptyList(),
    /** Heading outline: (level, heading text, page). */
    val headings: List<ParsedHeading> = emptyList(),
    val pageCount: Int = 0,
    /** True when OCR produced the text (images / scanned PDFs). */
    val fromOcr: Boolean = false
) {
    init {
        require(text.isNotEmpty()) { "Parsed document text must not be empty" }
    }
}

data class ParsedHeading(
    val level: Int,
    val text: String,
    val page: Int? = null
)

/**
 * Extracts clean text from a single attachment format. Implementations are
 * pure JVM where possible so they are unit-testable on the host; only the
 * OCR and PDF-rendering paths touch Android APIs.
 */
interface DocumentParser {
    val format: AttachmentType

    /** True when this parser can handle the given file (by extension/magic). */
    fun supports(file: File): Boolean

    /** Extracts text. Throws [Exception] with a user-readable message on failure. */
    fun parse(file: File, ocrLanguage: String = "en"): ParsedDocument
}
