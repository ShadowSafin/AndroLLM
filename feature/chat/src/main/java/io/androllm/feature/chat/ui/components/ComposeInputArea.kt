package io.androllm.feature.chat.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Modern compose input box with auto-growing text field, send/stop animation,
 * character counter, clear button, and attachment/mic placeholders.
 */
@Composable
fun ComposeInputArea(
    text: String,
    onTextChanged: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onStopGeneration: () -> Unit,
    isGenerating: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxCharacterLimit: Int = 4096
) {
    val context = LocalContext.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Attach Button Placeholder
                IconButton(
                    onClick = {
                        Toast.makeText(context, "File attachments coming soon", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = "Attach file",
                        tint = MaterialTheme.colorScheme.outline
                    )
                }

                // Auto-growing Text Field
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        if (it.length <= maxCharacterLimit) onTextChanged(it)
                    },
                    placeholder = { Text("Message AndroLLM...") },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 5,
                    enabled = enabled && !isGenerating,
                    trailingIcon = {
                        if (text.isNotEmpty() && !isGenerating) {
                            IconButton(
                                onClick = { onTextChanged("") },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear input",
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )

                // Mic Placeholder (when text empty & not generating)
                if (text.isEmpty() && !isGenerating) {
                    IconButton(
                        onClick = {
                            Toast.makeText(context, "Voice input coming soon", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice input",
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                // Send / Stop Action Button
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isGenerating) MaterialTheme.colorScheme.error
                            else if (text.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = {
                            if (isGenerating) {
                                onStopGeneration()
                            } else if (text.isNotBlank()) {
                                onSendMessage(text)
                            }
                        },
                        enabled = isGenerating || text.isNotBlank()
                    ) {
                        AnimatedContent(
                            targetState = isGenerating,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "SendStopToggle"
                        ) { generating ->
                            if (generating) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop generating",
                                    tint = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.size(22.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Send,
                                    contentDescription = "Send message",
                                    tint = if (text.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Character Counter
            if (text.length > maxCharacterLimit * 0.7) {
                Text(
                    text = "${text.length} / $maxCharacterLimit",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (text.length >= maxCharacterLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 16.dp, top = 2.dp)
                )
            }
        }
    }
}
