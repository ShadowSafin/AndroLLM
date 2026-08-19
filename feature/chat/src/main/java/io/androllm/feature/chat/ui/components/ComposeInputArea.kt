package io.androllm.feature.chat.ui.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.androllm.core.ui.theme.DeskHairline
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.DeskPillShape
import io.androllm.core.ui.theme.DeskSlipShape
import io.androllm.core.ui.theme.DeskWalnutRaised
import io.androllm.core.ui.theme.EmberRed
import io.androllm.core.ui.theme.InkOnLamp
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.ledger

/**
 * The writing slip — the desk's composer. A parchment panel with a ruled
 * underline where the words go, and one terracotta capsule for sending the
 * letter. While the model writes back, the capsule becomes a stop.
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
    maxCharacterLimit: Int = 4096,
    onAttachFiles: (List<Uri>) -> Unit = {},
    /**
     * True when the active model supports attachments (cloud only). When
     * false the paperclip is removed entirely — no empty gap, the composer
     * row just holds the text field and the send capsule.
     */
    showAttachButton: Boolean = true
) {
    val context = LocalContext.current

    // Attach menu: Files / Images / Camera / Gallery. Camera captures into a
    // temp file whose URI is handed to the same processing pipeline.
    var attachMenuOpen by remember { mutableStateOf(false) }
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) onAttachFiles(uris) }

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) onAttachFiles(uris) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) cameraImageUri?.let { onAttachFiles(listOf(it)) }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris -> if (uris.isNotEmpty()) onAttachFiles(uris) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = DeskSlipShape,
        color = MaterialTheme.ledger.deskWalnutRaised,
        border = BorderStroke(1.dp, if (text.isNotEmpty()) MaterialTheme.ledger.lampAmber.copy(alpha = 0.45f) else MaterialTheme.ledger.deskHairline),
        shadowElevation = 10.dp
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
                // Attach — a quiet marginal note with Files/Images/Camera/Gallery.
                // Hidden entirely for local models (no gap: the row simply
                // lacks the button — attachments are cloud-only).
                if (showAttachButton) Box {
                    IconButton(
                        onClick = { attachMenuOpen = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Attach file",
                            tint = MaterialTheme.ledger.deskInk
                        )
                    }
                    DropdownMenu(
                        expanded = attachMenuOpen,
                        onDismissRequest = { attachMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Files") },
                            onClick = {
                                attachMenuOpen = false
                                fileLauncher.launch(
                                    arrayOf(
                                        "application/pdf", "application/msword",
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                        "text/plain", "text/markdown", "text/csv", "application/json", "text/html", "application/epub+zip"
                                    )
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Images") },
                            onClick = {
                                attachMenuOpen = false
                                imageLauncher.launch("image/*")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Camera") },
                            onClick = {
                                attachMenuOpen = false
                                val photoFile = java.io.File(
                                    context.cacheDir,
                                    "camera_${System.currentTimeMillis()}.jpg"
                                )
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    photoFile
                                )
                                cameraImageUri = uri
                                cameraLauncher.launch(uri)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Gallery") },
                            onClick = {
                                attachMenuOpen = false
                                galleryLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        if (it.length <= maxCharacterLimit) onTextChanged(it)
                    },
                    placeholder = {
                        Text(
                            text = "Send a message…",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.ledger.deskInkFaint
                            )
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 2.dp),
                    shape = DeskPillShape,
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
                                    tint = MaterialTheme.ledger.deskInk
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
                        focusedTextColor = MaterialTheme.ledger.deskPaper,
                        unfocusedTextColor = MaterialTheme.ledger.deskPaper,
                        cursorColor = MaterialTheme.ledger.lampAmber
                    )
                )

                // Mic — a quiet marginal note.
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
                            tint = MaterialTheme.ledger.deskInk
                        )
                    }
                }

                // The single amber capsule: send, or stop while the lamp writes.
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isGenerating -> MaterialTheme.ledger.emberRed
                                text.isNotBlank() -> MaterialTheme.ledger.lampAmber
                                else -> MaterialTheme.ledger.deskInkFaint.copy(alpha = 0.5f)
                            }
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
                                    tint = MaterialTheme.ledger.inkOnLamp,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Send,
                                    contentDescription = "Send message",
                                    tint = if (text.isNotBlank()) MaterialTheme.ledger.inkOnLamp else MaterialTheme.ledger.deskInk,
                                    modifier = Modifier.size(18.dp)
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
                    color = if (text.length >= maxCharacterLimit) MaterialTheme.ledger.emberRed else MaterialTheme.ledger.deskInk,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 16.dp, top = 2.dp)
                )
            }
        }
    }
}
