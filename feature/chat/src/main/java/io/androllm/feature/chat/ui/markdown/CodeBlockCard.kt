package io.androllm.feature.chat.ui.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Modern code snippet block with syntax highlighting, language badge, copy button, line wrapping toggle, and horizontal scroll.
 */
@Composable
fun CodeBlockCard(
    code: String,
    language: String = "",
    modifier: Modifier = Modifier,
    initialWrapLines: Boolean = false
) {
    val context = LocalContext.current
    var isWrapped by remember { mutableStateOf(initialWrapLines) }
    var isCopied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val displayLanguage = language.ifBlank { "code" }.lowercase(Locale.getDefault())

    val syntaxHighlightedCode = remember(code, displayLanguage) {
        highlightSyntax(code, displayLanguage)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E1E2E), // Dark code editor background
            contentColor = Color(0xFFCDD6F4)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            // Header Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF181825),
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = displayLanguage,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFCBA6F7)
                        )
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { isWrapped = !isWrapped },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.WrapText,
                                contentDescription = "Toggle line wrap",
                                tint = if (isWrapped) Color(0xFFA6E3A1) else Color(0xFFA6ADC8),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText(displayLanguage, code)
                                clipboard.setPrimaryClip(clip)
                                isCopied = true
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                scope.launch {
                                    delay(2000)
                                    isCopied = false
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            AnimatedVisibility(
                                visible = isCopied,
                                enter = fadeIn(),
                                exit = fadeOut()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Copied",
                                    tint = Color(0xFFA6E3A1),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            AnimatedVisibility(
                                visible = !isCopied,
                                enter = fadeIn(),
                                exit = fadeOut()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy code",
                                    tint = Color(0xFFA6ADC8),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Code Content
            SelectionContainer {
                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .then(
                            if (isWrapped) Modifier else Modifier.horizontalScroll(scrollState)
                        )
                ) {
                    Text(
                        text = syntaxHighlightedCode,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        softWrap = isWrapped,
                        color = Color(0xFFCDD6F4)
                    )
                }
            }
        }
    }
}

/**
 * Syntax highlighter tokenizing common keywords, strings, comments, numbers, and types for popular languages.
 */
private fun highlightSyntax(code: String, language: String): AnnotatedString {
    return buildAnnotatedString {
        append(code)

        val keywordColor = Color(0xFFCBA6F7)  // Lavender
        val stringColor = Color(0xFFA6E3A1)   // Mint Green
        val commentColor = Color(0xFF6C7086)  // Muted Grey
        val numberColor = Color(0xFFFAB387)   // Peach
        val typeColor = Color(0xFF89B4FA)     // Blue
        val fnColor = Color(0xFF89DCEB)       // Cyan

        // Highlight single line and multi-line comments
        val commentRegex = Regex("(//.*$|#.*$|/\\*.*?\\*/)", RegexOption.MULTILINE)
        commentRegex.findAll(code).forEach { match ->
            addStyle(SpanStyle(color = commentColor), match.range.first, match.range.last + 1)
        }

        // Highlight strings
        val stringRegex = Regex("(\"[^\"]*\"|'[^']*'|`[^`]*`)")
        stringRegex.findAll(code).forEach { match ->
            addStyle(SpanStyle(color = stringColor), match.range.first, match.range.last + 1)
        }

        // Highlight Numbers
        val numberRegex = Regex("\\b\\d+(_\\d+)*(\\.\\d+)?\\b")
        numberRegex.findAll(code).forEach { match ->
            addStyle(SpanStyle(color = numberColor), match.range.first, match.range.last + 1)
        }

        // Keywords list for supported languages
        val keywords = setOf(
            "val", "var", "fun", "class", "object", "interface", "package", "import",
            "return", "if", "else", "when", "for", "while", "do", "try", "catch", "finally",
            "public", "private", "protected", "internal", "override", "abstract", "sealed",
            "data", "enum", "companion", "by", "init", "suspend", "coroutine",
            "def", "lambda", "yield", "async", "await", "import", "from", "as",
            "let", "const", "function", "export", "default", "type", "typeof",
            "struct", "impl", "trait", "pub", "use", "fn", "match", "mod", "mut",
            "select", "insert", "update", "delete", "where", "from", "join", "group", "order",
            "void", "int", "double", "float", "boolean", "char", "long", "short", "byte"
        )

        val wordRegex = Regex("\\b[A-Za-z_][A-Za-z0-9_]*\\b")
        wordRegex.findAll(code).forEach { match ->
            val word = match.value
            val start = match.range.first
            val end = match.range.last + 1

            if (keywords.contains(word)) {
                addStyle(SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold), start, end)
            } else if (word.firstOrNull()?.isUpperCase() == true) {
                addStyle(SpanStyle(color = typeColor), start, end)
            } else if (end < code.length && code[end] == '(') {
                addStyle(SpanStyle(color = fnColor), start, end)
            }
        }
    }
}
