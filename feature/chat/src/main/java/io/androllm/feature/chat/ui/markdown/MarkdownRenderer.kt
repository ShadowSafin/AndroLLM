package io.androllm.feature.chat.ui.markdown

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed interface MarkdownNode {
    data class Header(val level: Int, val content: String) : MarkdownNode
    data class Paragraph(val text: String) : MarkdownNode
    data class CodeBlock(val language: String, val code: String) : MarkdownNode
    data class Blockquote(val text: String) : MarkdownNode
    data class BulletList(val items: List<String>) : MarkdownNode
    data class NumberedList(val items: List<String>) : MarkdownNode
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownNode
    data class Callout(val kind: String, val text: String) : MarkdownNode
    data object HorizontalRule : MarkdownNode
    data class ImagePlaceholder(val altText: String, val url: String) : MarkdownNode
    data class MathBlock(val expression: String) : MarkdownNode
}

/**
 * High performance Markdown parsing and rendering component for Jetpack Compose.
 */
@Composable
fun MarkdownRenderer(
    markdownText: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    codeWrapping: Boolean = false
) {
    val nodes = remember(markdownText) {
        parseMarkdown(markdownText)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        nodes.forEach { node ->
            when (node) {
                is MarkdownNode.Header -> HeaderItem(node, textColor)
                is MarkdownNode.Paragraph -> ParagraphItem(node.text, textColor)
                is MarkdownNode.CodeBlock -> CodeBlockCard(code = node.code, language = node.language, initialWrapLines = codeWrapping)
                is MarkdownNode.Blockquote -> BlockquoteItem(node.text, textColor)
                is MarkdownNode.BulletList -> BulletListItem(node.items, textColor)
                is MarkdownNode.NumberedList -> NumberedListItem(node.items, textColor)
                is MarkdownNode.Table -> TableItem(node)
                is MarkdownNode.Callout -> CalloutItem(node, textColor)
                is MarkdownNode.HorizontalRule -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                is MarkdownNode.ImagePlaceholder -> ImagePlaceholderItem(node)
                is MarkdownNode.MathBlock -> MathBlockItem(node)
            }
        }
    }
}

@Composable
private fun HeaderItem(header: MarkdownNode.Header, textColor: Color) {
    val style = when (header.level) {
        1 -> MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp, color = textColor)
        2 -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp, color = textColor)
        3 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = textColor)
        else -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = textColor)
    }
    Text(
        text = parseFormattedText(
            header.content,
            textColor,
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurface,
            MaterialTheme.colorScheme.primary
        ),
        style = style,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun ParagraphItem(text: String, textColor: Color) {
    val context = LocalContext.current
    val codeChipBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val codeChipFg = MaterialTheme.colorScheme.onSurface
    val linkColor = MaterialTheme.colorScheme.primary
    val annotatedString = remember(text, textColor, codeChipBg, codeChipFg, linkColor) {
        parseFormattedText(text, textColor, codeChipBg, codeChipFg, linkColor)
    }

    ClickableText(
        text = annotatedString,
        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
        onClick = { offset ->
            annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(annotation.item))
                    runCatching { context.startActivity(intent) }
                }
        }
    )
}

@Composable
private fun BlockquoteItem(text: String, textColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(40.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(2.dp)
                )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = parseFormattedText(
                    text,
                    textColor,
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    MaterialTheme.colorScheme.onSurface,
                    MaterialTheme.colorScheme.primary
                ),
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
private fun BulletListItem(items: List<String>, textColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "• ",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                )
                Text(
                    text = parseFormattedText(
                        item,
                        textColor,
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.colorScheme.onSurface,
                        MaterialTheme.colorScheme.primary
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NumberedListItem(items: List<String>, textColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "${index + 1}. ",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                )
                Text(
                    text = parseFormattedText(
                        item,
                        textColor,
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.colorScheme.onSurface,
                        MaterialTheme.colorScheme.primary
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TableItem(table: MarkdownNode.Table) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                table.headers.forEach { header ->
                    Text(
                        text = header,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            // Data Rows
            table.rows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    row.forEach { cell ->
                        Text(
                            text = cell,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImagePlaceholderItem(image: MarkdownNode.ImagePlaceholder) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Image, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = image.altText.ifBlank { "Image Attachment" },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                if (image.url.isNotBlank()) {
                    Text(
                        text = image.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun MathBlockItem(math: MarkdownNode.MathBlock) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Functions, contentDescription = "Math", tint = MaterialTheme.colorScheme.secondary)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = math.expression,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/**
 * Simple line-by-line Markdown parser into structured node list.
 */
private fun parseMarkdown(text: String): List<MarkdownNode> {
    val nodes = mutableListOf<MarkdownNode>()
    val lines = text.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // Code block check
        if (line.trimStart().startsWith("```")) {
            val language = line.trimStart().removePrefix("```").trim()
            val codeBuilder = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeBuilder.append(lines[i]).append("\n")
                i++
            }
            nodes.add(MarkdownNode.CodeBlock(language, codeBuilder.toString().trimEnd()))
            i++
            continue
        }

        // Math Block check ($$ ... $$)
        if (line.trim().startsWith("$$")) {
            val mathBuilder = StringBuilder()
            val singleLineMath = line.trim().removePrefix("$$").removeSuffix("$$").trim()
            if (line.trim().endsWith("$$") && line.trim().length > 4) {
                nodes.add(MarkdownNode.MathBlock(singleLineMath))
                i++
                continue
            }
            i++
            while (i < lines.size && !lines[i].trim().endsWith("$$")) {
                mathBuilder.append(lines[i]).append("\n")
                i++
            }
            nodes.add(MarkdownNode.MathBlock(mathBuilder.toString().trim()))
            i++
            continue
        }

        // Horizontal Rule
        if (line.trim() == "---" || line.trim() == "***" || line.trim() == "___") {
            nodes.add(MarkdownNode.HorizontalRule)
            i++
            continue
        }

        // Header check
        val headerMatch = Regex("^(#{1,6})\\s+(.+)").find(line)
        if (headerMatch != null) {
            val level = headerMatch.groupValues[1].length
            val content = headerMatch.groupValues[2]
            nodes.add(MarkdownNode.Header(level, content))
            i++
            continue
        }

        // AI section cards — callouts in the style of GitHub alerts and bold
        // lead-ins (**Tip:**, **Note:**, **Warning:**, **Example:**) and emoji
        // prefixes (💡, ⚠️, 📝). Rendered as tinted cards, not quotes.
        val callout = parseCallout(line)
        if (callout != null) {
            val kind = callout.first
            val textBuilder = StringBuilder(callout.second)
            i++
            // Gather following quoted lines as the card body.
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                val rest = lines[i].trimStart().removePrefix(">").trim()
                if (rest.isNotBlank()) textBuilder.append('\n').append(rest)
                i++
            }
            nodes.add(MarkdownNode.Callout(kind, textBuilder.toString().trim()))
            continue
        }

        // Blockquote check
        if (line.trimStart().startsWith(">")) {
            val quoteText = line.trimStart().removePrefix(">").trim()
            nodes.add(MarkdownNode.Blockquote(quoteText))
            i++
            continue
        }

        // Image check (![alt](url))
        val imageMatch = Regex("!\\[(.*?)\\]\\((.*?)\\)").find(line)
        if (imageMatch != null) {
            nodes.add(MarkdownNode.ImagePlaceholder(imageMatch.groupValues[1], imageMatch.groupValues[2]))
            i++
            continue
        }

        // Table check
        if (line.contains("|") && i + 1 < lines.size && lines[i + 1].contains("---")) {
            val headers = line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            i += 2 // skip header and separator
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].contains("|")) {
                val rowCells = lines[i].split("|").map { it.trim() }.filter { it.isNotEmpty() }
                if (rowCells.isNotEmpty()) rows.add(rowCells)
                i++
            }
            nodes.add(MarkdownNode.Table(headers, rows))
            continue
        }

        // Bullet list check (* or -)
        if (line.trimStart().startsWith("* ") || line.trimStart().startsWith("- ")) {
            val listItems = mutableListOf<String>()
            while (i < lines.size && (lines[i].trimStart().startsWith("* ") || lines[i].trimStart().startsWith("- "))) {
                listItems.add(lines[i].trimStart().substring(2))
                i++
            }
            nodes.add(MarkdownNode.BulletList(listItems))
            continue
        }

        // Numbered list check (1. 2. etc)
        val numMatch = Regex("^(\\d+)\\.\\s+(.+)").find(line.trimStart())
        if (numMatch != null) {
            val listItems = mutableListOf<String>()
            while (i < lines.size && Regex("^(\\d+)\\.\\s+(.+)").containsMatchIn(lines[i].trimStart())) {
                val match = Regex("^(\\d+)\\.\\s+(.+)").find(lines[i].trimStart())
                if (match != null) listItems.add(match.groupValues[2])
                i++
            }
            nodes.add(MarkdownNode.NumberedList(listItems))
            continue
        }

        // Default Paragraph
        if (line.isNotBlank()) {
            nodes.add(MarkdownNode.Paragraph(line.trim()))
        }
        i++
    }

    return nodes
}

/**
 * Formats inline bold (**), italic (*), strikethrough (~~), inline code (`code`), and links ([text](url)).
 */
private val CALLOUT_ALIASES = mapOf(
    "tip" to "Tip",
    "note" to "Note",
    "warning" to "Warning",
    "warn" to "Warning",
    "caution" to "Warning",
    "important" to "Important",
    "info" to "Note",
    "example" to "Example",
    "question" to "Note",
    "danger" to "Warning"
)

/**
 * Detects an AI section card at the start of [line]. Returns (kind, text) or
 * null. Recognises GitHub alert syntax `> [!TIP]`, bold lead-ins such as
 * `**Tip:** ...` / `**Note:** ...`, and emoji prefixes.
 */
private fun parseCallout(line: String): Pair<String, String>? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null

    // GitHub alerts: > [!NOTE] rest
    val alert = Regex("^>\\s*\\[!\\s*([A-Za-z]+)\\]\\s*(.*)$").find(trimmed)
    if (alert != null) {
        val kind = CALLOUT_ALIASES[alert.groupValues[1].lowercase()] ?: return null
        return kind to alert.groupValues[2].trim()
    }

    // Bold lead-ins: **Tip:** text / **Note:** text
    val bold = Regex("^\\*\\*([A-Za-z]+)\\s*:\\*\\*\\s*(.+)$").find(trimmed)
    if (bold != null) {
        val kind = CALLOUT_ALIASES[bold.groupValues[1].lowercase()] ?: return null
        return kind to bold.groupValues[2].trim()
    }

    // Emoji prefixes: 💡, ⚠️, 📝, ❗, ✨
    val emojiKind = when {
        trimmed.startsWith("💡") || trimmed.startsWith("✨") || trimmed.startsWith("📌") -> "Tip"
        trimmed.startsWith("⚠️") || trimmed.startsWith("❗") || trimmed.startsWith("🔴") -> "Warning"
        trimmed.startsWith("📝") || trimmed.startsWith("📖") -> "Note"
        trimmed.startsWith("🎯") || trimmed.startsWith("🧩") || trimmed.startsWith("🖼️") -> "Example"
        else -> null
    }
    if (emojiKind != null) {
        // Strip every leading non-alphanumeric code point (emoji + variation
        // selectors such as U+FE0F) plus following whitespace.
        val body = trimmed.replaceFirst(Regex("^[^\\p{L}\\p{N}]+\\s*"), "")
        return emojiKind to body
    }

    return null
}

private data class CalloutPalette(
    val bg: Color,
    val border: Color,
    val accent: Color,
    val glyph: String
)

@Composable
private fun CalloutItem(callout: MarkdownNode.Callout, textColor: Color) {
    val palette = when (callout.kind) {
        "Tip" -> CalloutPalette(Color(0xFFEDF4E6), Color(0xFFA9BF8A), Color(0xFF5F7D3E), "💡")
        "Warning" -> CalloutPalette(Color(0xFFFBF0DC), Color(0xFFDDB968), Color(0xFF92681E), "⚠️")
        "Example" -> CalloutPalette(Color(0xFFFBE9E0), Color(0xFFE3B39A), Color(0xFFA85E3E), "✨")
        else -> CalloutPalette(Color(0xFFE9EFF4), Color(0xFFA7BFD4), Color(0xFF52708C), "📌")
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        color = palette.bg,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, palette.border)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = palette.glyph, fontSize = 13.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = callout.kind.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 1.4.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.accent
                    )
                )
            }
            if (callout.text.isNotBlank()) {
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = parseFormattedText(
                        callout.text,
                        textColor,
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.colorScheme.onSurface,
                        MaterialTheme.colorScheme.primary
                    ),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        lineHeight = 21.sp,
                        color = textColor.copy(alpha = 0.92f)
                    )
                )
            }
        }
    }
}

private fun parseFormattedText(
    text: String,
    defaultColor: Color,
    inlineCodeBackground: Color = Color(0xFFEFEEE6),
    inlineCodeForeground: Color = Color(0xFF4A4945),
    linkColor: Color = Color(0xFFB3573E)
): AnnotatedString {
    return buildAnnotatedString {
        var currentIndex = 0

        // Parse link regex first [text](url)
        val linkRegex = Regex("\\[(.*?)\\]\\((.*?)\\)")
        val matches = linkRegex.findAll(text).toList()

        if (matches.isEmpty()) {
            appendFormattedInline(text, defaultColor, inlineCodeBackground, inlineCodeForeground)
        } else {
            for (match in matches) {
                val start = match.range.first
                val end = match.range.last + 1
                if (start > currentIndex) {
                    appendFormattedInline(text.substring(currentIndex, start), defaultColor, inlineCodeBackground, inlineCodeForeground)
                }

                val linkText = match.groupValues[1]
                val url = match.groupValues[2]

                val linkStart = length
                append(linkText)
                addStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                        fontWeight = FontWeight.Medium
                    ),
                    linkStart,
                    length
                )
                addStringAnnotation("URL", url, linkStart, length)
                currentIndex = end
            }
            if (currentIndex < text.length) {
                appendFormattedInline(text.substring(currentIndex), defaultColor, inlineCodeBackground, inlineCodeForeground)
            }
        }
    }
}

private fun AnnotatedString.Builder.appendFormattedInline(
    text: String,
    defaultColor: Color,
    inlineCodeBackground: Color,
    inlineCodeForeground: Color
) {
    var index = 0
    val inlineCodeRegex = Regex("`([^`]+)`")
    val boldRegex = Regex("\\*\\*([^*]+)\\*\\*")
    val italicRegex = Regex("\\*([^*]+)\\*")

    val tokens = text.split("`")
    if (tokens.size > 1) {
        for (i in tokens.indices) {
            if (i % 2 == 1) {
                // Inline Code chip
                val start = length
                append(" ${tokens[i]} ")
                addStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = inlineCodeBackground,
                        color = inlineCodeForeground,
                        fontSize = 13.sp
                    ),
                    start,
                    length
                )
            } else {
                appendStyleFormatted(tokens[i], defaultColor)
            }
        }
    } else {
        appendStyleFormatted(text, defaultColor)
    }
}

private fun AnnotatedString.Builder.appendStyleFormatted(text: String, defaultColor: Color) {
    // Process bold and italic
    val parts = text.split("**")
    for (i in parts.indices) {
        if (i % 2 == 1) {
            val start = length
            append(parts[i])
            addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = defaultColor), start, length)
        } else {
            val subParts = parts[i].split("*")
            for (j in subParts.indices) {
                if (j % 2 == 1) {
                    val start = length
                    append(subParts[j])
                    addStyle(SpanStyle(fontStyle = FontStyle.Italic, color = defaultColor), start, length)
                } else {
                    val start = length
                    append(subParts[j])
                    addStyle(SpanStyle(color = defaultColor), start, length)
                }
            }
        }
    }
}
