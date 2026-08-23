package io.androllm.feature.chat.ui.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
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
 * Now with AI-generated link detection, highlight, and safety warning.
 */
@Composable
fun MarkdownRenderer(
    markdownText: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    codeWrapping: Boolean = false,
    warnBeforeOpeningAiLinks: Boolean = true
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
                is MarkdownNode.Header -> HeaderItem(node, textColor, warnBeforeOpeningAiLinks)
                is MarkdownNode.Paragraph -> ParagraphItem(node.text, textColor, warnBeforeOpeningAiLinks)
                is MarkdownNode.CodeBlock -> CodeBlockCard(code = node.code, language = node.language, initialWrapLines = codeWrapping)
                is MarkdownNode.Blockquote -> BlockquoteItem(node.text, textColor, warnBeforeOpeningAiLinks)
                is MarkdownNode.BulletList -> BulletListItem(node.items, textColor, warnBeforeOpeningAiLinks)
                is MarkdownNode.NumberedList -> NumberedListItem(node.items, textColor, warnBeforeOpeningAiLinks)
                is MarkdownNode.Table -> TableItem(node, textColor, warnBeforeOpeningAiLinks)
                is MarkdownNode.Callout -> CalloutItem(node, textColor, warnBeforeOpeningAiLinks)
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
private fun HeaderItem(header: MarkdownNode.Header, textColor: Color, warnBeforeOpeningAiLinks: Boolean = true) {
    val style = when (header.level) {
        1 -> MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp, color = textColor)
        2 -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp, color = textColor)
        3 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = textColor)
        else -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = textColor)
    }
    LinkAwareClickableText(
        text = header.content,
        textColor = textColor,
        style = style,
        warnBeforeOpeningAiLinks = warnBeforeOpeningAiLinks,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun ParagraphItem(text: String, textColor: Color, warnBeforeOpeningAiLinks: Boolean = true) {
    LinkAwareClickableText(
        text = text,
        textColor = textColor,
        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
        warnBeforeOpeningAiLinks = warnBeforeOpeningAiLinks
    )
}

@Composable
private fun BlockquoteItem(text: String, textColor: Color, warnBeforeOpeningAiLinks: Boolean = true) {
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
            LinkAwareClickableText(
                text = text,
                textColor = textColor,
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                warnBeforeOpeningAiLinks = warnBeforeOpeningAiLinks,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
private fun BulletListItem(items: List<String>, textColor: Color, warnBeforeOpeningAiLinks: Boolean = true) {
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
                LinkAwareClickableText(
                    text = item,
                    textColor = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                    warnBeforeOpeningAiLinks = warnBeforeOpeningAiLinks,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NumberedListItem(items: List<String>, textColor: Color, warnBeforeOpeningAiLinks: Boolean = true) {
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
                LinkAwareClickableText(
                    text = item,
                    textColor = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                    warnBeforeOpeningAiLinks = warnBeforeOpeningAiLinks,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TableItem(
    table: MarkdownNode.Table,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    warnBeforeOpeningAiLinks: Boolean = true
) {
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
                    LinkAwareClickableText(
                        text = header,
                        textColor = textColor,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        warnBeforeOpeningAiLinks = warnBeforeOpeningAiLinks,
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
                        LinkAwareClickableText(
                            text = cell,
                            textColor = textColor,
                            style = MaterialTheme.typography.bodySmall,
                            warnBeforeOpeningAiLinks = warnBeforeOpeningAiLinks,
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
private fun CalloutItem(callout: MarkdownNode.Callout, textColor: Color, warnBeforeOpeningAiLinks: Boolean = true) {
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
                LinkAwareClickableText(
                    text = callout.text,
                    textColor = textColor,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        lineHeight = 21.sp,
                        color = textColor.copy(alpha = 0.92f)
                    ),
                    warnBeforeOpeningAiLinks = warnBeforeOpeningAiLinks
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
        // Use AiLinkUtils to handle both markdown links and plain URLs in one pass,
        // stripping trailing punctuation and validating schemes.
        val segments = AiLinkUtils.splitTextWithLinks(text)
        if (segments.isEmpty()) {
            appendFormattedInline(text, defaultColor, inlineCodeBackground, inlineCodeForeground)
            return@buildAnnotatedString
        }
        // If splitTextWithLinks returned only plain segments (no links), fallback to inline formatting.
        val hasLinks = segments.any { it is AiLinkUtils.Segment.Link }
        if (!hasLinks) {
            // No links detected — preserve original text with inline formatting
            appendFormattedInline(text, defaultColor, inlineCodeBackground, inlineCodeForeground)
            return@buildAnnotatedString
        }

        for (segment in segments) {
            when (segment) {
                is AiLinkUtils.Segment.Plain -> {
                    appendFormattedInline(segment.text, defaultColor, inlineCodeBackground, inlineCodeForeground)
                }
                is AiLinkUtils.Segment.Link -> {
                    val linkStart = length
                    // Display text is either markdown display or the URL itself
                    val display = segment.displayText
                    // Append display text with link style
                    append(display)
                    // Append external-link icon as part of the same clickable span so tapping the icon opens the link
                    val icon = " ↗"
                    append(icon)
                    val linkEnd = length
                    addStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.Medium
                        ),
                        linkStart,
                        linkEnd
                    )
                    // Annotate the whole span (display + icon) with the URL
                    addStringAnnotation("URL", segment.url, linkStart, linkEnd)
                    // Also add an annotation for the icon alone to make it clear it's an external link
                    // (optional, not needed)
                }
            }
        }
    }
}

@Composable
internal fun LinkAwareClickableText(
    text: String,
    textColor: Color,
    style: TextStyle,
    warnBeforeOpeningAiLinks: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val codeChipBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val codeChipFg = MaterialTheme.colorScheme.onSurface
    val linkColor = MaterialTheme.colorScheme.primary
    val annotatedString = remember(text, textColor, codeChipBg, codeChipFg, linkColor) {
        parseFormattedText(text, textColor, codeChipBg, codeChipFg, linkColor)
    }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    var longPressUrl by remember { mutableStateOf<String?>(null) }

    // Warning dialog for tap
    pendingUrl?.let { url ->
        AiLinkWarningDialog(
            url = url,
            onOpen = { toOpen ->
                // Double-validate before opening
                if (AiLinkUtils.isValidForOpening(toOpen)) {
                    openAiLinkSafely(context, toOpen)
                } else {
                    Toast.makeText(context, "Invalid link", Toast.LENGTH_SHORT).show()
                }
                pendingUrl = null
            },
            onDismiss = { pendingUrl = null }
        )
    }

    // Long-press menu
    longPressUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { longPressUrl = null },
            icon = {
                Icon(
                    imageVector = Icons.Filled.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            },
            title = { Text("Link options", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.primary),
                        maxLines = 3
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Choose an action for this AI-generated link.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Copy
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Link", url))
                    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                    longPressUrl = null
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy")
                    }
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        // Share
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, url)
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(shareIntent, "Share link"))
                        }
                        longPressUrl = null
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Share, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Share")
                        }
                    }
                    TextButton(onClick = { longPressUrl = null }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    Text(
        text = annotatedString,
        style = style,
        modifier = modifier.pointerInput(annotatedString, warnBeforeOpeningAiLinks) {
            detectTapGestures(
                onTap = { pos ->
                    layoutResult?.let { result ->
                        val offset = result.getOffsetForPosition(pos)
                        annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                            .firstOrNull()?.let { ann ->
                                val url = ann.item
                                if (!AiLinkUtils.isValidForOpening(url)) {
                                    Toast.makeText(context, "Invalid link", Toast.LENGTH_SHORT).show()
                                    return@let
                                }
                                // Respect settings toggle: if warn disabled, open directly
                                if (warnBeforeOpeningAiLinks) {
                                    pendingUrl = url
                                } else {
                                    // Still validate scheme before opening
                                    if (AiLinkUtils.isAllowedScheme(url)) {
                                        openAiLinkSafely(context, url)
                                    } else {
                                        Toast.makeText(context, "Blocked unsafe URL", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                    }
                },
                onLongPress = { pos ->
                    layoutResult?.let { result ->
                        val offset = result.getOffsetForPosition(pos)
                        annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                            .firstOrNull()?.let { ann ->
                                longPressUrl = ann.item
                            }
                    }
                }
            )
        },
        onTextLayout = { layoutResult = it }
    )
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
