package io.androllm.feature.chat.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.androllm.core.ui.theme.AzureBlue
import io.androllm.core.ui.theme.CloudCapsuleShape
import io.androllm.core.ui.theme.CloudGlassBorder
import io.androllm.core.ui.theme.CloudGlassBorderHighlight
import io.androllm.core.ui.theme.CloudGlassSurface
import io.androllm.core.ui.theme.CloudWhite
import io.androllm.core.ui.theme.ElectricBlue
import io.androllm.core.ui.theme.MoonSilver
import io.androllm.core.ui.theme.SkyBlue
import io.androllm.core.ui.theme.SoftCyan

/**
 * Cloud Intelligence Input Area.
 * Floating cloud capsule input bar with ambient glow and spring send button.
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
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = CloudCapsuleShape,
        color = CloudGlassSurface,
        border = BorderStroke(1.dp, if (text.isNotEmpty()) CloudGlassBorderHighlight else CloudGlassBorder),
        shadowElevation = 12.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Attach Button
                IconButton(
                    onClick = {
                        Toast.makeText(context, "File attachments coming soon", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = "Attach file",
                        tint = MoonSilver.copy(alpha = 0.7f)
                    )
                }

                // Text Field Capsule
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        if (it.length <= maxCharacterLimit) onTextChanged(it)
                    },
                    placeholder = {
                        Text(
                            text = "Ask AndroLLM...",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MoonSilver.copy(alpha = 0.5f)
                            )
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 2.dp),
                    shape = CloudCapsuleShape,
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
                                    tint = MoonSilver.copy(alpha = 0.7f)
                                )
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = CloudWhite,
                        unfocusedTextColor = CloudWhite
                    )
                )

                // Mic Placeholder
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
                            tint = MoonSilver.copy(alpha = 0.7f)
                        )
                    }
                }

                // Floating Send / Stop Button Capsule
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isGenerating) Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.error))
                            else if (text.isNotBlank()) Brush.horizontalGradient(listOf(ElectricBlue, SoftCyan))
                            else Brush.horizontalGradient(listOf(CloudGlassSurface, CloudGlassSurface))
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
                                    tint = CloudWhite,
                                    modifier = Modifier.size(22.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Send,
                                    contentDescription = "Send message",
                                    tint = if (text.isNotBlank()) CloudWhite else MoonSilver.copy(alpha = 0.4f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (text.length > maxCharacterLimit * 0.7) {
                Text(
                    text = "${text.length} / $maxCharacterLimit",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (text.length >= maxCharacterLimit) MaterialTheme.colorScheme.error else MoonSilver.copy(alpha = 0.5f),
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 16.dp, top = 2.dp)
                )
            }
        }
    }
}
