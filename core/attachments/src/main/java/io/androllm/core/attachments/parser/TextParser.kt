package io.androllm.core.attachments.parser

import io.androllm.core.attachments.model.AttachmentType
import java.io.File
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.regex.Pattern

/**
 * Parsers for plain-text family formats: TXT, Markdown, CSV, JSON and HTML.
 * All are pure JVM and unit-testable.
 */
object TextParsers {

    val HEADING_RE = Regex("^(#{1,6})\\s+(.+)$")
    val TAG_RE = Pattern.compile("<[^>]+>")
    private val WHITESPACE_RE = Regex("[ \\t\\x0B\\f\\r]+")

    /**
     * Normalizes extracted text: trims each line and collapses runs of
     * whitespace (spaces/tabs) inside a line to a single space. Blank lines
     * are dropped so paragraph boundaries stay explicit (`\n\n` is produced
     * by the parsers when a real break exists).
     */
    fun normalize(text: String): String =
        text.lines()
            .map { it.trim().replace(WHITESPACE_RE, " ") }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()

    /** Splits normalized text into pages; single page for non-paginated formats. */
    fun singlePage(text: String): ParsedDocument = ParsedDocument(
        text = text,
        pages = listOf(text),
        pageCount = 1
    )
}

/** Plain text (.txt / .log): read raw, normalize. */
class TxtParser : DocumentParser {
    override val format = AttachmentType.TXT
    override fun supports(file: File) = file.extension.equals("txt", true) || file.extension.equals("log", true)

    override fun parse(file: File, ocrLanguage: String): ParsedDocument {
        val text = TextParsers.normalize(file.readText())
        return TextParsers.singlePage(text)
    }
}

/** Markdown: strip inline markers but KEEP headings (level + text). */
class MarkdownParser : DocumentParser {
    override val format = AttachmentType.MARKDOWN
    override fun supports(file: File) = file.extension.equals("md", true) || file.extension.equals("markdown", true)

    override fun parse(file: File, ocrLanguage: String): ParsedDocument {
        val raw = file.readText()
        val headings = mutableListOf<ParsedHeading>()
        val cleaned = raw.lines().map { line ->
            val heading = TextParsers.HEADING_RE.find(line.trim())
            if (heading != null) {
                val level = heading.groupValues[1].length
                val text = heading.groupValues[2].trim()
                headings += ParsedHeading(level, text, page = null)
                text
            } else {
                // Strip inline markers (code backticks, bold/italic, links,
                // strike-through); keep table pipes so CSV-ish tables read well.
                line.trim()
                    .replace(Regex("`+"), "")
                    .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
                    .replace(Regex("(?<![*])\\*\\*(.+?)\\*\\*(?![*])"), "$1")
                    .replace(Regex("(?<![*])\\*(?!\\s)(.+?)(?<!\\s)\\*"), "$1")
                    .replace(Regex("__(.+?)__"), "$1")
                    .replace(Regex("(?<!_)_(?!\\s)(.+?)(?<!\\s)_"), "$1")
                    .replace(Regex("~~(.+?)~~"), "$1")
            }
        }.joinToString("\n")
        val text = TextParsers.normalize(cleaned)
        return ParsedDocument(text, listOf(text), headings, 1)
    }
}

/**
 * CSV: reflow each record as a readable line. Fields are split with a
 * small quote-aware state machine so quoted commas stay intact.
 */
class CsvParser : DocumentParser {
    override val format = AttachmentType.CSV
    override fun supports(file: File) = file.extension.equals("csv", true)

    override fun parse(file: File, ocrLanguage: String): ParsedDocument {
        val lines = file.readText().lines().filter { it.isNotBlank() }
        val reflowed = lines.map { line -> splitCsvLine(line).joinToString(" | ") }
        val text = TextParsers.normalize(reflowed.joinToString("\n"))
        return TextParsers.singlePage(text)
    }

    private fun splitCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    fields += current.toString().trim()
                    current.setLength(0)
                }
                else -> current.append(c)
            }
            i++
        }
        fields += current.toString().trim()
        return fields
    }
}

/** JSON: pretty-print so key/value pairs read naturally. */
class JsonParser : DocumentParser {
    override val format = AttachmentType.JSON
    override fun supports(file: File) = file.extension.equals("json", true)

    override fun parse(file: File, ocrLanguage: String): ParsedDocument {
        val raw = file.readText()
        val text = TextParsers.normalize(
            raw.replace(Regex("[{}\\[\\],]"), "\n")
                .replace(Regex("\"(.*?)\"\\s*:"), "$1: ")
                .replace("\"", "")
        )
        return TextParsers.singlePage(text)
    }
}

/**
 * HTML: strip tags, decode entities, preserve heading outline. Uses only the
 * JDK regex/HTML decoder so it runs in JVM unit tests.
 */
class HtmlParser : DocumentParser {
    override val format = AttachmentType.HTML
    override fun supports(file: File) =
        file.extension.equals("html", true) || file.extension.equals("htm", true)

    override fun parse(file: File, ocrLanguage: String): ParsedDocument {
        val raw = file.readText()
        val headings = mutableListOf<ParsedHeading>()

        // Strip <script>/<style> blocks first so their contents never leak.
        var body = raw.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        // Preserve heading tags as markers so we can harvest the outline.
        val headingMatches = Regex("(?is)<h([1-6])[^>]*>(.*?)</h\\1>").findAll(body)
        for (m in headingMatches) {
            val level = m.groupValues[1].toInt()
            val text = decodeEntities(stripTags(m.groupValues[2])).trim()
            if (text.isNotEmpty()) headings += ParsedHeading(level, text, page = null)
        }
        body = body.replace(Regex("(?is)<h([1-6])[^>]*>"), "\n\n")
            .replace(Regex("(?is)</h\\1>"), "\n\n")
        val text = TextParsers.normalize(decodeEntities(stripTags(body)))
        return ParsedDocument(text, listOf(text), headings, 1)
    }

    private fun stripTags(s: String): String = TextParsers.TAG_RE.matcher(s).replaceAll(" ")

    private fun decodeEntities(s: String): String = try {
        URLDecoder.decode(s.replace("&amp;", "&"), Charset.defaultCharset().name())
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
    } catch (_: Exception) {
        s
    }
}
