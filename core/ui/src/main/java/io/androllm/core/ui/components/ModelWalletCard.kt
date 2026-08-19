package io.androllm.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.models.Model
import io.androllm.core.ui.theme.DeskCardShape
import io.androllm.core.ui.theme.DeskHairline
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.DeskWalnut
import io.androllm.core.ui.theme.DeskWalnutRaised
import io.androllm.core.ui.theme.InkOnLamp
import io.androllm.core.ui.theme.LampAmber

/**
 * The lettered index card — a model as an entry in the desk's own ledger.
 * Serif name, monospace meta, the lit terracotta seal when loaded, and the
 * single accent action. Downloaded models read as cards on the page; the
 * rest sit dim in the stack.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelWalletCard(
    model: Model,
    isActive: Boolean,
    isDownloaded: Boolean,
    /** Effective context read from the active LiteRT model metadata. */
    activeContextLength: Int? = null,
    onLoadClick: () -> Unit,
    onDownloadClick: () -> Unit,
    modifier: Modifier = Modifier,
    menuItems: (@Composable () -> Unit)? = null
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val sizeGbText = remember(model.fileSize) {
        if (model.fileSize > 0) String.format("%.1f", model.fileSize / (1024.0 * 1024.0 * 1024.0)) else "3.8"
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(DeskCardShape)
            .shadow(
                elevation = if (isActive) 14.dp else 8.dp,
                shape = DeskCardShape,
                ambientColor = Color(0x66000000),
                spotColor = if (isActive) LampAmber.copy(alpha = 0.18f) else Color(0x88000000)
            )
            .background(if (isActive) DeskWalnutRaised else DeskWalnut)
            .border(
                width = if (isActive) 1.dp else 1.dp,
                color = if (isActive) LampAmber.copy(alpha = 0.65f) else DeskHairline,
                shape = DeskCardShape
            )
            .clickable {
                if (isDownloaded) onLoadClick() else onDownloadClick()
            }
            .padding(20.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LampDot(size = 10.dp, lit = isActive)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = model.name,
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = DeskPaper
                            ),
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${model.parameters.ifBlank { "7B" }} · ${model.quantization.ifBlank { "Q4_K_M" }}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                letterSpacing = 1.sp,
                                color = if (isActive) LampAmber else DeskInk
                            ),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isActive) {
                        LampDot(size = 6.dp, lit = true)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "LOADED",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = LampAmber
                            )
                        )
                    }

                    if (menuItems != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Box {
                            IconButton(
                                onClick = { menuExpanded = true },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More options",
                                    tint = DeskInk
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                menuItems()
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModelSpecPill(label = "RAM", value = "${model.recommendedRamGb} GB")
                val contextLength = if (isActive) activeContextLength ?: model.contextLength else model.contextLength
                ModelSpecPill(label = "CONTEXT", value = "${(contextLength / 1024).coerceAtLeast(1)}K")
                ModelSpecPill(label = "FAMILY", value = model.family)
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isActive) "LOADED · in memory" else if (isDownloaded) "INSTALLED · on device" else "NOT DOWNLOADED",
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 1.2.sp,
                        color = if (isActive) LampAmber else DeskInkFaint
                    ),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(12.dp))

                if (isDownloaded) {
                    CloudCapsuleButton(
                        text = if (isActive) "Unload" else "Load into memory",
                        onClick = onLoadClick,
                        icon = if (isActive) Icons.Default.Stop else Icons.Default.PlayArrow
                    )
                } else {
                    CloudCapsuleButton(
                        text = "Take ($sizeGbText GB)",
                        onClick = onDownloadClick,
                        icon = Icons.Default.Download
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelSpecPill(label: String, value: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(ParchmentInset)
            .border(1.dp, DeskHairline, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = "$label $value",
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 0.8.sp,
                color = DeskInk
            ),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

private val ParchmentInset = Color(0xFFEFEEE6)
