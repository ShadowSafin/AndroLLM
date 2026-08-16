package io.androllm.core.attachments.parser

import io.androllm.core.attachments.model.AttachmentType
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Lightweight parsers for the OOXML/EPUB zip containers. DOCX/PPTX/XLSX and
 * EPUB are all ZIP archives of XML; the JDK's built-in ZipFile + DOM parser
 * extract the text without the multi-MB Apache POI dependency. Pure JVM so
 * these are unit-testable on the host.
 */
object ZipXml {

    /** Returns the text of every <tag> descendant of [root] (concatenated). */
    fun textOf(root: Element, tag: String): String {
        val out = StringBuilder()
        val nodes = root.getElementsByTagName(tag)
        for (i in 0 until nodes.length) out.append(nodes.item(i).textContent).append(' ')
        return out.toString().trim()
    }

    /** Returns the first entry whose name equals [name] or ends with [suffix]. */
    fun readEntry(zip: ZipFile, name: String? = null, suffix: String? = null): String? {
        val entry = zip.entries().asSequence().firstOrNull {
            (name != null && it.name.equals(name, ignoreCase = true)) ||
                (suffix != null && it.name.endsWith(suffix, ignoreCase = true))
        } ?: return null
        return zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
    }

    fun parseXml(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray())).documentElement
    }

    /** Collects text of every XML entry whose name ends with [suffix], joined by [separator]. */
    fun collectEntries(zip: ZipFile, suffix: String, tag: String, separator: String = "\n"): String {
        val parts = mutableListOf<String>()
        for (entry in zip.entries()) {
            if (!entry.name.endsWith(suffix, ignoreCase = true)) continue
            val xml = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
            val text = runCatching { textOf(parseXml(xml), tag) }.getOrDefault("")
            if (text.isNotBlank()) parts += text
        }
        return parts.joinToString(separator)
    }
}

/** DOCX: extract every <w:t> run from word/document.xml, preserving paragraphs. */
class DocxParser : DocumentParser {
    override val format = AttachmentType.DOCX
    override fun supports(file: File) = file.extension.equals("docx", true)

    override fun parse(file: File, ocrLanguage: String): ParsedDocument {
        ZipFile(file).use { zip ->
            val documentXml = ZipXml.readEntry(zip, name = "word/document.xml") ?: return TextParsers.singlePage("")
            val root = ZipXml.parseXml(documentXml)
            val paragraphs = root.getElementsByTagName("w:p")
            val out = StringBuilder()
            for (i in 0 until paragraphs.length) {
                val text = ZipXml.textOf(paragraphs.item(i) as Element, "w:t")
                if (text.isNotBlank()) out.append(text).append('\n')
            }
            val normalized = TextParsers.normalize(out.toString())
            if (normalized.isEmpty()) throw IllegalArgumentException("No text found in DOCX document")
            return TextParsers.singlePage(normalized)
        }
    }
}

/** PPTX: extract the speaker text of every slide (<a:t> runs), slide by slide. */
class PptxParser : DocumentParser {
    override val format = AttachmentType.PPTX
    override fun supports(file: File) = file.extension.equals("pptx", true)

    override fun parse(file: File, ocrLanguage: String): ParsedDocument {
        ZipFile(file).use { zip ->
            val slides = mutableListOf<String>()
            zip.entries().asSequence()
                .filter { it.name.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                .sortedBy { it.name.substringAfterLast('/').removePrefix("slide").removeSuffix(".xml").toIntOrNull() ?: 0 }
                .forEach { entry ->
                    val xml = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                    val text = runCatching { ZipXml.textOf(ZipXml.parseXml(xml), "a:t") }.getOrDefault("")
                    if (text.isNotBlank()) slides += text
                }
            val normalized = TextParsers.normalize(slides.joinToString("\n\n"))
            if (normalized.isEmpty()) throw IllegalArgumentException("No text found in PPTX presentation")
            return ParsedDocument(
                text = normalized,
                pages = slides.map { TextParsers.normalize(it) }.filter { it.isNotEmpty() },
                pageCount = slides.size
            )
        }
    }
}

/**
 * XLSX: read the shared strings table (cell values) plus every worksheet,
 * one row per line so tabular data stays readable.
 */
class XlsxParser : DocumentParser {
    override val format = AttachmentType.XLSX
    override fun supports(file: File) = file.extension.equals("xlsx", true)

    override fun parse(file: File, ocrLanguage: String): ParsedDocument {
        ZipFile(file).use { zip ->
            // Shared strings: the pool of every non-formula string cell value.
            val sharedStrings = mutableListOf<String>()
            ZipXml.readEntry(zip, name = "xl/sharedStrings.xml")?.let { ssXml ->
                val root = ZipXml.parseXml(ssXml)
                val items = root.getElementsByTagName("si")
                for (i in 0 until items.length) {
                    sharedStrings += ZipXml.textOf(items.item(i) as Element, "t")
                }
            }
            val sheets = mutableListOf<String>()
            zip.entries().asSequence()
                .filter { it.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
                .sortedBy { it.name.substringAfterLast('/').removePrefix("sheet").removeSuffix(".xml").toIntOrNull() ?: 0 }
                .forEach { entry ->
                    val xml = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                    val root = runCatching { ZipXml.parseXml(xml) }.getOrNull() ?: return@forEach
                    val rows = root.getElementsByTagName("row")
                    val sheetLines = mutableListOf<String>()
                    for (r in 0 until rows.length) {
                        val row = rows.item(r) as Element
                        val cells = row.getElementsByTagName("c")
                        val values = mutableListOf<String>()
                        for (c in 0 until cells.length) {
                            val cell = cells.item(c) as Element
                            val value = cell.getElementsByTagName("v").let { nodes ->
                                if (nodes.length > 0) nodes.item(0).textContent else ""
                            }
                            if (value.isNotBlank()) {
                                // Inline strings carry the value in <is><t>; shared
                                // strings index into the pool via <v>.
                                val inline = runCatching { ZipXml.textOf(cell, "t") }.getOrDefault("")
                                val resolved = when {
                                    inline.isNotBlank() -> inline
                                    value.toIntOrNull() != null && value.toInt() in sharedStrings.indices ->
                                        sharedStrings[value.toInt()]
                                    else -> value
                                }
                                if (resolved.isNotBlank()) values += resolved
                            }
                        }
                        if (values.isNotEmpty()) sheetLines += values.joinToString(" | ")
                    }
                    if (sheetLines.isNotEmpty()) sheets += sheetLines.joinToString("\n")
                }
            val normalized = TextParsers.normalize(sheets.joinToString("\n\n"))
            if (normalized.isEmpty()) throw IllegalArgumentException("No text found in XLSX workbook")
            return TextParsers.singlePage(normalized)
        }
    }
}

/**
 * EPUB: read the spine from content.opf, then concatenate each XHTML chapter
 * in reading order (headings preserved from <h1..h6>).
 */
class EpubParser : DocumentParser {
    override val format = AttachmentType.EPUB
    override fun supports(file: File) = file.extension.equals("epub", true)

    override fun parse(file: File, ocrLanguage: String): ParsedDocument {
        ZipFile(file).use { zip ->
            val opf = zip.entries().asSequence()
                .firstOrNull { it.name.endsWith(".opf", ignoreCase = true) }
                ?: throw IllegalArgumentException("EPUB has no content.opf")
            val opfXml = zip.getInputStream(opf).readBytes().toString(Charsets.UTF_8)
            val root = ZipXml.parseXml(opfXml)
            // Resolve hrefs relative to the OPF directory.
            val baseDir = opf.name.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            val manifest = mutableMapOf<String, String>() // id -> href
            val manifestNodes = root.getElementsByTagName("item")
            for (i in 0 until manifestNodes.length) {
                val item = manifestNodes.item(i) as Element
                val id = item.getAttribute("id")
                val href = item.getAttribute("href")
                if (id.isNotBlank() && href.isNotBlank()) manifest[id] = href
            }
            val spine = mutableListOf<String>()
            val spineNodes = root.getElementsByTagName("itemref")
            for (i in 0 until spineNodes.length) {
                val idref = (spineNodes.item(i) as Element).getAttribute("idref")
                manifest[idref]?.let { spine += it }
            }
            val chapters = mutableListOf<String>()
            for (href in spine) {
                val entryName = baseDir + href
                val xml = runCatching {
                    zip.getInputStream(zip.getEntry(entryName))?.readBytes()?.toString(Charsets.UTF_8)
                }.getOrNull() ?: continue
                val chapter = runCatching {
                    // Reuse the HTML heading harvest for EPUB chapters.
                    val html = xml.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
                    val text = TextParsers.TAG_RE.matcher(html).replaceAll(" ")
                    TextParsers.normalize(text)
                }.getOrDefault("")
                if (chapter.isNotBlank()) chapters += chapter
            }
            val normalized = chapters.joinToString("\n\n")
            if (normalized.isEmpty()) throw IllegalArgumentException("No text found in EPUB book")
            return TextParsers.singlePage(normalized)
        }
    }
}
