package io.androllm.feature.coding.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import io.androllm.core.ui.theme.ledger

/**
 * Opens [url] in the device's DEFAULT BROWSER via ACTION_VIEW. The preview is
 * never rendered inside the app — the browser is the preview surface.
 * Returns false when the URL is blank or no browser could handle it.
 */
fun openInDefaultBrowser(context: Context, url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isBlank()) return false
    return runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, trimmed.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)
}

/**
 * Preview control panel with an IDE-like server lifecycle.
 *
 * Rules:
 * - The preview is ONLY available while a local server is actually running —
 *   until then this panel offers Start Preview, never a preview.
 * - The preview itself always opens in the device's DEFAULT BROWSER (tap the
 *   card) — it is never rendered inside the app.
 * - Start Preview launches the right local HTTP server (static serve or the
 *   project's real dev server) — never a raw file:// URL.
 * - Stop Preview shuts the server down.
 * - Startup failures show the captured server log.
 */
@Composable
fun PreviewPanel(
    url: String,
    previewStatus: PreviewUiStatus = PreviewUiStatus.IDLE,
    previewTitle: String? = null,
    previewSuggestion: String? = null,
    frameworkLabel: String? = null,
    phase: String? = null,
    canStartServer: Boolean = false,
    serverRunning: Boolean = false,
    serverLog: String? = null,
    onClose: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    onStartServer: (() -> Unit)? = null,
    onStopServer: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val ready = previewStatus == PreviewUiStatus.READY && url.isNotBlank()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.ledger.deskWalnut),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            // ── Header: status + title + actions ─────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when (previewStatus) {
                                PreviewUiStatus.READY -> Color(0xFF34C759).copy(alpha = 0.15f)
                                PreviewUiStatus.FAILED -> Color(0xFFEF4444).copy(alpha = 0.12f)
                                PreviewUiStatus.SCANNING -> Color(0xFFF59E0B).copy(alpha = 0.12f)
                                else -> MaterialTheme.ledger.deskHairlineSoft
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Visibility,
                        contentDescription = null,
                        tint = when (previewStatus) {
                            PreviewUiStatus.READY -> Color(0xFF1B7A2B)
                            PreviewUiStatus.FAILED -> Color(0xFFEF4444)
                            PreviewUiStatus.SCANNING -> Color(0xFFD97706)
                            else -> MaterialTheme.ledger.deskInk
                        },
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            phase != null -> phase
                            ready -> "Preview Ready"
                            previewStatus == PreviewUiStatus.SCANNING -> "Scanning..."
                            previewStatus == PreviewUiStatus.FAILED -> "Preview Failed"
                            else -> "Preview"
                        },
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = when (previewStatus) {
                                PreviewUiStatus.READY -> Color(0xFF1B7A2B)
                                PreviewUiStatus.FAILED -> Color(0xFF991B1B)
                                else -> MaterialTheme.ledger.lampDeep
                            },
                            letterSpacing = 0.6.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (frameworkLabel != null) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.ledger.deskHairlineSoft
                            ) {
                                Text(
                                    frameworkLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = MaterialTheme.ledger.deskInk,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            previewTitle ?: if (ready) url else "No server running",
                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                IconButton(onClick = { onRefresh?.invoke() }) {
                    Icon(Icons.Filled.Refresh, "Re-check preview", tint = MaterialTheme.ledger.deskPaperDim, modifier = Modifier.size(20.dp))
                }
                if (serverRunning && onStopServer != null) {
                    IconButton(onClick = { onStopServer() }) {
                        Icon(Icons.Filled.Stop, "Stop preview server", tint = Color(0xFFEF4444), modifier = Modifier.size(20.dp))
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, "Close preview", tint = MaterialTheme.ledger.deskInk, modifier = Modifier.size(20.dp))
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── Main area: browser-open card when ready, start prompt otherwise ──
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0F172A))
            ) {
                if (ready) {
                    // The server is running: tapping opens the URL in the
                    // device's default browser — never inside the app.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { openInDefaultBrowser(context, url) }
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF34C759).copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.OpenInBrowser,
                                contentDescription = null,
                                tint = Color(0xFF4ADE80),
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Open in browser",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF8FAFC)
                            )
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            url,
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = Color(0xFF4ADE80),
                                fontFamily = FontFamily.Monospace
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "The preview opens in your default browser app.",
                            style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF94A3B8)),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    // No running server → no preview. Offer Start Preview when a
                    // server can be launched for the detected target.
                    Column(
                        Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Filled.Language, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (previewStatus == PreviewUiStatus.SCANNING) "Scanning for a preview..."
                            else "No preview available yet.",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = Color(0xFFF8FAFC))
                        )
                        Text(
                            previewSuggestion
                                ?: "The preview becomes available once a local server is running. Create an index.html or a project with a dev server, then tap Start Preview.",
                            style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF94A3B8)),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (canStartServer && onStartServer != null) {
                                StartPreviewButton(onClick = { onStartServer() }, busy = phase != null)
                            }
                            TextButton(onClick = { onRefresh?.invoke() }) {
                                Text("Scan again", fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.lampDeep)
                            }
                        }
                    }
                }
            }

            // ── Server log on failure (visible, not silent) ─────────────────
            if (previewStatus == PreviewUiStatus.FAILED && !serverLog.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0F172A))
                        .padding(10.dp)
                ) {
                    Text(
                        "SERVER LOG",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = Color(0xFF94A3B8)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        serverLog,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = Color(0xFFE2E8F0),
                        maxLines = 12
                    )
                }
            }

            // ── Footer: precise lifecycle status ───────────────────────────
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (phase != null) {
                    CircularProgressIndicator(
                        color = MaterialTheme.ledger.lampAmber,
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when {
                        phase != null -> phase
                        ready -> "Server running • tap the card to open in your browser"
                        previewStatus == PreviewUiStatus.SCANNING -> "Scanning workspace for previewable pages..."
                        previewStatus == PreviewUiStatus.FAILED -> "Preview failed — see the server log above, then retry"
                        canStartServer -> "Tap Start Preview to launch the local server"
                        else -> "The preview is available only while a local server is running"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInkFaint),
                    modifier = Modifier.weight(1f),
                    maxLines = 2
                )
            }
        }
    }
}

/** Prominent "Start Preview" action — launches the local HTTP server. */
@Composable
private fun StartPreviewButton(onClick: () -> Unit, busy: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (busy) MaterialTheme.ledger.deskHairlineSoft else MaterialTheme.ledger.lampAmber,
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(50))
            .clickable(enabled = !busy, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (busy) {
                CircularProgressIndicator(
                    color = MaterialTheme.ledger.lampDeep,
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(6.dp))
                Text("Starting...", fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.lampDeep)
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Start Preview", fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}
