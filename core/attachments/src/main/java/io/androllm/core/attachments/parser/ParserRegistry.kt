package io.androllm.core.attachments.parser

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.attachments.model.AttachmentType
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registry of all document parsers. The processor asks each parser whether it
 * can handle the file, so adding a format (audio/video transcripts, etc.) is
 * a one-line registration.
 */
@Singleton
class ParserRegistry @Inject constructor(
    @ApplicationContext context: Context
) {
    private val imageOcr: MlKitOcrEngine = MlKitOcrEngine(context)

    private val parsers: List<DocumentParser> = buildList {
        add(TxtParser())
        add(MarkdownParser())
        add(CsvParser())
        add(JsonParser())
        add(HtmlParser())
        add(DocxParser())
        add(PptxParser())
        add(XlsxParser())
        add(EpubParser())
        add(PdfParser(context, imageOcr))
        add(ImageParser(imageOcr))
    }

    /** Finds a parser for [file], or null when the format is unsupported. */
    fun find(file: File): DocumentParser? = parsers.firstOrNull { it.supports(file) }

    /** True when any registered parser can handle [file]. */
    fun supports(file: File): Boolean = find(file) != null

    /** The type the registry would assign to [name] (for unknown extensions). */
    fun typeFor(name: String): AttachmentType = AttachmentType.fromFileName(name)
}
