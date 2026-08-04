package io.androllm.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.models.LLMModel
import io.androllm.core.ui.theme.CloudGlassBorder
import io.androllm.core.ui.theme.CloudGlassBorderHighlight
import io.androllm.core.ui.theme.CloudWhite
import io.androllm.core.ui.theme.MoonSilver
import io.androllm.core.ui.theme.RevolutCyberCyan
import io.androllm.core.ui.theme.RevolutGoldTier
import io.androllm.core.ui.theme.RevolutNeonEmerald
import io.androllm.core.ui.theme.SkyBlue
import io.androllm.core.ui.theme.SunsetCloudDeepOrange
import io.androllm.core.ui.theme.SunsetCloudOrange
import io.androllm.core.ui.theme.SunsetCloudPeach
import io.androllm.core.ui.theme.SunsetGlowAmber

/**
 * Revolut Credit Card / Virtual Account Card Style for GGUF AI Models.
 * Presents a holographic metallic card with model specs, memory usage, and load controls.
 */
@Composable
fun ModelWalletCard(
    model: LLMModel,
    isActive: Boolean,
    isDownloaded: Boolean,
    onLoadClick: () -> Unit,
    onDownloadClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                if (isActive) {
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF1E1B4B),
                            Color(0xFF0F172A),
                            Color(0xFF2E1065)
                        )
                    )
                } else {
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0x730E1626),
                            Color(0xBF070B14)
                        )
                    )
                }
            )
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                brush = Brush.horizontalGradient(
                    if (isActive) {
                        listOf(SunsetCloudOrange, SunsetGlowAmber, RevolutCyberCyan)
                    } else {
                        listOf(CloudGlassBorderHighlight, CloudGlassBorder)
                    }
                ),
                shape = RoundedCornerShape(24.dp)
            )
            .clickable {
                if (isDownloaded) onLoadClick() else onDownloadClick()
            }
            .padding(20.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Row: Model Name & Active Status Chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Holographic Chip Icon
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(SunsetCloudPeach, SunsetGlowAmber, RevolutGoldTier)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = model.name,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = CloudWhite
                            )
                        )
                        Text(
                            text = "${model.parameterSize} • ${model.quantization}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = SunsetCloudPeach,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }

                if (isActive) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(RevolutNeonEmerald.copy(alpha = 0.2f))
                            .border(1.dp, RevolutNeonEmerald, CircleShape)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(RevolutNeonEmerald)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "ACTIVE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                color = RevolutNeonEmerald
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Spec Pills Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModelSpecPill(label = "RAM", value = "${model.ramRequiredGb} GB")
                ModelSpecPill(label = "Context", value = "${model.contextLength / 1024}k")
                ModelSpecPill(label = "Family", value = model.family.name)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Button Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Rating Stars
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = RevolutGoldTier,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "4.9",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = CloudWhite
                        )
                    )
                }

                if (isDownloaded) {
                    CloudCapsuleButton(
                        text = if (isActive) "Unload Model" else "Load Into Memory",
                        onClick = onLoadClick,
                        icon = if (isActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                        gradient = if (isActive) {
                            Brush.horizontalGradient(listOf(Color(0xFFDC2626), Color(0xFF991B1B)))
                        } else {
                            Brush.horizontalGradient(listOf(SunsetCloudOrange, SunsetGlowAmber))
                        }
                    )
                } else {
                    CloudCapsuleButton(
                        text = "Download (${model.sizeGb} GB)",
                        onClick = onDownloadClick,
                        icon = Icons.Default.Download,
                        gradient = Brush.horizontalGradient(listOf(SkyBlue, RevolutCyberCyan))
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
            .background(Color(0x33E2E8F0))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = "$label: $value",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MoonSilver
        )
    }
}
