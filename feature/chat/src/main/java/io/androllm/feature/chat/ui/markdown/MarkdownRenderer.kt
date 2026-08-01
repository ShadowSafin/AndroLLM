package io.androllm.feature.chat.ui.markdown

import android.content.Intent
import android.net.Uri
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
        text = parseFormattedText(header.content, textColor),
        style = style,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun ParagraphItem(text: String, textColor: Color) {
    val context = LocalContext.current
    val annotatedString = remember(text, textColor) { parseFormattedText(text, textColor) }

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
                text = parseFormattedText(text, textColor),
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
                    text = parseFormattedText(item, textColor),
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
                    text = parseFormattedText(item, textColor),
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
private fun parseFormattedText(text: String, defaultColor: Color): AnnotatedString {
    return buildAnnotatedString {
        var currentIndex = 0

        // Parse link regex first [text](url)
        val linkRegex = Regex("\\[(.*?)\\]\\((.*?)\\)")
        val matches = linkRegex.findAll(text).toList()

        if (matches.isEmpty()) {
            appendFormattedInline(text, defaultColor)
        } else {
            for (match in matches) {
                val start = match.range.first
                val end = match.range.last + 1
                if (start > currentIndex) {
                    appendFormattedInline(text.substring(currentIndex, start), defaultColor)
                }

                val linkText = match.groupValues[1]
                val url = match.groupValues[2]

                val linkStart = length
                append(linkText)
                addStyle(
                    SpanStyle(
                        color = Color(0xFF89B4FA),
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
                appendFormattedInline(text.substring(currentIndex), defaultColor)
            }
        }
    }
}

private fun AnnotatedString.Builder.appendFormattedInline(text: String, defaultColor: Color) {
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
                        background = Color(0xFF313244),
                        color = Color(0xFFF5E0DC),
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
