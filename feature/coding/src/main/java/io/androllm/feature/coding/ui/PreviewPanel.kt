package io.androllm.feature.coding.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import io.androllm.core.ui.theme.ledger

/**
 * Premium preview panel with an IDE-like server lifecycle:
 *
 * - Start Preview launches the right local HTTP server (static serve or the
 *   project's real dev server) — never a raw file:// URL.
 * - Refresh reloads the page WITHOUT restarting the server when unnecessary.
 * - Stop Preview shuts the server down.
 * - The preview card only shows "ready" once the server actually responds;
 *   until then the precise phase is shown ("Starting local server...",
 *   "Waiting for localhost:PORT...").
 * - Startup failures show the captured server log.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewPanel(
    initialUrl: String,
    refreshTick: Int = 0,
    previewStatus: PreviewUiStatus = if (initialUrl.isNotBlank()) PreviewUiStatus.READY else PreviewUiStatus.IDLE,
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
    onOpenInBrowser: ((String) -> Unit)? = null,
    onReportFailed: ((String) -> Unit)? = null,
    onReportOpened: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var urlText by remember(initialUrl) { mutableStateOf(initialUrl) }
    var loadedUrl by remember { mutableStateOf(initialUrl) }
    var internalTick by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(initialUrl.isNotBlank()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Sync external initialUrl changes (auto-detection picked a new target)
    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotBlank() && initialUrl != loadedUrl) {
            urlText = initialUrl
            loadedUrl = initialUrl
            errorMessage = null
            loading = true
            internalTick++
        }
    }
    // External refreshTick (file edits / build) forces reload even when URL same
    LaunchedEffect(refreshTick) {
        if (refreshTick != 0 && loadedUrl.isNotBlank()) {
            internalTick++
            loading = true
            errorMessage = null
        }
    }

    fun navigate(raw: String) {
        val target = normalizeUrl(raw)
        urlText = target
        errorMessage = null
        loading = true
        loadedUrl = target
        internalTick++
        onReportOpened?.invoke(target)
    }

    fun doRefresh() {
        if (loadedUrl.isNotBlank()) {
            errorMessage = null
            loading = true
            internalTick++
        }
        onRefresh?.invoke()
    }

    fun openExternally() {
        val target = loadedUrl.ifBlank { urlText }
        if (target.isBlank()) return
        onOpenInBrowser?.invoke(target)
        // Also try system browser as fallback
        runCatching {
            val uri = target.toUri()
            val intent = Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
        }
    }

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
                            previewStatus == PreviewUiStatus.READY -> "Preview Ready"
                            previewStatus == PreviewUiStatus.SCANNING -> "Scanning…"
                            previewStatus == PreviewUiStatus.FAILED -> "Preview Failed"
                            previewStatus == PreviewUiStatus.NOT_AVAILABLE -> "Preview"
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
                            previewTitle ?: when {
                                loadedUrl.isBlank() -> "No target yet"
                                loadedUrl.startsWith("file://") -> loadedUrl.substringAfterLast("/")
                                else -> loadedUrl
                            },
                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                IconButton(onClick = { doRefresh() }) {
                    Icon(Icons.Filled.Refresh, "Refresh preview", tint = MaterialTheme.ledger.deskPaperDim, modifier = Modifier.size(20.dp))
                }
                if (serverRunning && onStopServer != null) {
                    IconButton(onClick = { onStopServer() }) {
                        Icon(Icons.Filled.Stop, "Stop preview server", tint = Color(0xFFEF4444), modifier = Modifier.size(20.dp))
                    }
                }
                if (loadedUrl.isNotBlank()) {
                    IconButton(onClick = { openExternally() }) {
                        Icon(Icons.Filled.OpenInBrowser, "Open in browser", tint = MaterialTheme.ledger.lampDeep, modifier = Modifier.size(20.dp))
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, "Close preview", tint = MaterialTheme.ledger.deskInk, modifier = Modifier.size(20.dp))
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── URL bar (editable, with Go) ────────────────────────────────
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { navigate(urlText) }),
                placeholder = { Text("http://localhost:5173", style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInkFaint)) },
                trailingIcon = {
                    TextButton(onClick = { navigate(urlText) }) { Text("Go", fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.lampDeep) }
                },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.ledger.lampAmber,
                    unfocusedBorderColor = MaterialTheme.ledger.deskHairline
                )
            )

            Spacer(Modifier.height(10.dp))

            // ── WebView viewport ─────────────────────────────────────────────
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
            ) {
                if (loadedUrl.isBlank()) {
                    // Empty state with explanation + manual fallback guidance
                    Column(
                        Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Filled.Language, contentDescription = null, tint = MaterialTheme.ledger.deskInkFaint, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "No preview URL yet.",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper)
                        )
                        Text(
                            previewSuggestion ?: "Create an index.html or start a dev server (npm run dev, python -m http.server …). The preview opens automatically when a previewable page is detected.",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        if (previewSuggestion != null) {
                            Text(
                                previewSuggestion,
                                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.lampDeep),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (canStartServer && onStartServer != null && previewStatus != PreviewUiStatus.READY) {
                                StartPreviewButton(onClick = { onStartServer() }, busy = phase != null)
                            }
                            TextButton(onClick = { doRefresh() }) { Text("Scan again", fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.lampDeep) }
                        }
                        Text(
                            "Manual fallback: enter a URL above and tap Go.",
                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInkFaint),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                } else {
                    key(loadedUrl, internalTick) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.allowFileAccess = true
                                    settings.allowFileAccessFromFileURLs = true
                                    settings.allowUniversalAccessFromFileURLs = false
                                    settings.allowContentAccess = true
                                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            loading = false
                                            errorMessage = null
                                            onReportOpened?.invoke(url ?: loadedUrl)
                                        }

                                        override fun onReceivedError(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                            error: WebResourceError?
                                        ) {
                                            if (request?.isForMainFrame == true) {
                                                loading = false
                                                val msg = "Could not load ${request.url} (${error?.description})"
                                                errorMessage = msg
                                                onReportFailed?.invoke(msg)
                                            }
                                        }
                                    }
                                    loadUrl(loadedUrl)
                                }
                            }
                        )
                    }
                    if (loading) {
                        CircularProgressIndicator(
                            color = MaterialTheme.ledger.lampAmber,
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(22.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    errorMessage?.let { message ->
                        Box(
                            Modifier.fillMaxSize().background(Color(0xEEFFFFFF)).padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    message,
                                    style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFB00020)),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { doRefresh() }) { Text("Retry", color = MaterialTheme.ledger.lampDeep) }
                                    TextButton(onClick = { openExternally() }) { Text("Open in browser", color = MaterialTheme.ledger.lampDeep) }
                                }
                                Text(
                                    "Manual fallback: check the URL or start the dev server, then refresh.",
                                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInkFaint),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
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
                        previewStatus == PreviewUiStatus.READY -> "Served over a local HTTP server • refreshes after edits"
                        previewStatus == PreviewUiStatus.SCANNING -> "Scanning workspace for previewable pages…"
                        previewStatus == PreviewUiStatus.FAILED -> "Preview failed — see the server log above, then retry"
                        canStartServer -> "Tap Start Preview to launch the local server"
                        else -> "Preview refreshes automatically after code changes"
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
                Text("Starting…", fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.lampDeep)
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Start Preview", fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

/** Ensures the URL has a scheme so WebView.loadUrl doesn't treat it as a search. */
private fun normalizeUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    return if (
        trimmed.startsWith("http://") ||
        trimmed.startsWith("https://") ||
        trimmed.startsWith("file://")
    ) trimmed else "http://$trimmed"
}
