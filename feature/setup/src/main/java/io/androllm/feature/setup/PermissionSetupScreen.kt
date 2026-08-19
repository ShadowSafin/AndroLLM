package io.androllm.feature.setup

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.androllm.core.permissions.PermissionHandler
import io.androllm.core.permissions.PermissionState
import io.androllm.core.ui.components.CloudAtmosphericBackground
import io.androllm.core.ui.components.CloudBugdroidLogo
import io.androllm.core.ui.components.CloudCapsuleButton
import io.androllm.core.ui.components.CloudChip
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.EmberRed
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampDeep
import io.androllm.core.ui.theme.LampGlow
import io.androllm.core.ui.theme.LampHalo
import timber.log.Timber
import io.androllm.core.ui.theme.ledger

/**
 * First-launch "Let's set up AndroLLM" — appears once, right after a
 * successful sign-in, and explains each capability BEFORE its permission is
 * requested. The screen never traps the user: everything optional can be
 * skipped and managed later from Settings → Permissions & Access.
 *
 * Flow (per product spec):
 *   Sign In → auth success → (setup not completed) → this screen → Finish →
 *   Main app. Returning users with [PermissionSetupViewModel.setupCompleted]
 *   already true skip straight past via [LaunchedEffect].
 */
@Composable
fun PermissionSetupScreen(
    onFinished: () -> Unit,
    viewModel: PermissionSetupViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    val setupCompleted by viewModel.setupCompleted.collectAsStateWithLifecycle()

    // Bumped after a dialog result or after returning from system settings
    // (e.g. Accessibility) so every status is re-read live.
    var refreshTick by remember { mutableStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshTick++ }

    // Returning from Android's settings screens (accessibility, app details,
    // notification settings) → re-read every state immediately.
    LifecycleResumeEffect(Unit) {
        refreshTick++
        onPauseOrDispose { }
    }

    // Already completed in a previous session → straight to the main app.
    LaunchedEffect(setupCompleted) {
        if (setupCompleted == true) {
            Timber.i("[Setup] setupCompleted already true — skipping permission setup")
            onFinished()
        }
    }

    // Handler set is a static Hilt multibinding — cache it so status reads
    // only recompute when a request result or settings return bumps refreshTick.
    val handlers = remember { viewModel.handlers }
    val states = remember(handlers, refreshTick) {
        handlers.associateWith { viewModel.status(it, activity) }
    }

    // Grantable = features that apply to this device; the "Not required"
    // cards are informational and don't count toward progress.
    val grantable = states.filterValues { it != PermissionState.NOT_REQUIRED }
    val enabled = grantable.values.count { it == PermissionState.GRANTED }

    if (setupCompleted == true) return

    CloudAtmosphericBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // ── Top bar: mascot + Skip ─────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CloudBugdroidLogo(size = 44.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "AndroLLM",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.ledger.deskPaper
                    )
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { viewModel.finish(onFinished) }) {
                    Text(
                        text = "Skip",
                        style = MaterialTheme.typography.labelLarge.copy(
                            color = MaterialTheme.ledger.deskInk,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }

            // ── Header + progress ──────────────────────────────────────────
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Let's set up AndroLLM",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.ledger.deskPaper
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Give AndroLLM access to the features you want to use.",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.ledger.deskInk,
                        lineHeight = 22.sp
                    )
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (grantable.isEmpty()) "Nothing to grant — you're all set" else
                            "$enabled of ${grantable.size} enabled",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.ledger.deskInkFaint,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.6.sp
                        )
                    )
                    if (grantable.isNotEmpty()) {
                        Text(
                            text = "${(enabled * 100f / grantable.size).toInt()}%",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = MaterialTheme.ledger.lampDeep,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (grantable.isNotEmpty()) {
                    LinearProgressIndicator(
                        progress = { enabled.toFloat() / grantable.size },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.ledger.lampAmber,
                        trackColor = MaterialTheme.ledger.lampHalo.copy(alpha = 0.35f)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // ── Feature cards ──────────────────────────────────────────────
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val optional = states.filterValues { it != PermissionState.NOT_REQUIRED }
                if (optional.isNotEmpty()) {
                    item {
                        SectionLabel("OPTIONAL — ENABLE WHAT YOU'LL USE")
                    }
                    items(optional.keys.toList(), key = { it.id }) { handler ->
                        PermissionSetupCard(
                            handler = handler,
                            state = states.getValue(handler),
                            onRequest = {
                                viewModel.onRequested(handler)
                                val perms = viewModel.runtimePermissions(handler)
                                if (perms.isNotEmpty()) {
                                    permissionLauncher.launch(perms.toTypedArray())
                                } else {
                                    refreshTick++
                                }
                            },
                            onOpenSettings = {
                                val opened = viewModel.openSettings(handler)
                                if (!opened) {
                                    Timber.w("[Setup] no system screen available for ${handler.id}")
                                    refreshTick++
                                }
                            }
                        )
                    }
                }

                val notRequired = states.filterValues { it == PermissionState.NOT_REQUIRED }
                if (notRequired.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        SectionLabel("NOT REQUIRED ON THIS DEVICE")
                    }
                    items(notRequired.keys.toList(), key = { it.id }) { handler ->
                        NotRequiredCard(handler = handler)
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Everything stays on your device. You can change these any time in " +
                            "Settings → Permissions & Access, and revoke accessibility whenever you like.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.ledger.deskInkFaint,
                            lineHeight = 17.sp
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }

            // ── Finish ─────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                CloudCapsuleButton(
                    text = "Finish Setup",
                    onClick = { viewModel.finish(onFinished) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * One feature card: what it does, why it's asked, its live status, and the
 * right action for that status (Enable / Try Again / Open Settings).
 */
@Composable
internal fun PermissionSetupCard(
    handler: PermissionHandler,
    state: PermissionState,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.animateContentSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBox(handler.id)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = handler.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.ledger.deskPaper
                        )
                    )
                    Text(
                        text = handler.description,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.ledger.deskInk,
                            lineHeight = 16.sp
                        )
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                StatusChip(state)
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = handler.explanation,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.ledger.deskInkFaint,
                    lineHeight = 17.sp
                )
            )
            Spacer(modifier = Modifier.height(12.dp))

            when (state) {
                PermissionState.GRANTED -> {
                    Text(
                        text = if (handler.needsSettingsScreen) "✓ Accessibility enabled" else "✓ Granted",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.ledger.lampDeep,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                PermissionState.DENIED -> {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CloudCapsuleButton(
                                text = "Try Again",
                                onClick = onRequest,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            CloudCapsuleButton(
                                text = "Open Settings",
                                onClick = onOpenSettings,
                                gradient = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    listOf(MaterialTheme.ledger.lampGlow.copy(alpha = 0.3f), MaterialTheme.ledger.lampAmber.copy(alpha = 0.5f))
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Not enabled — some features may not work.",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.ledger.deskInkFaint
                            )
                        )
                    }
                }

                PermissionState.PERMANENTLY_DENIED -> {
                    Column {
                        Text(
                            text = "Permission blocked — open Android Settings to enable it.",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = MaterialTheme.ledger.emberRed,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        CloudCapsuleButton(
                            text = "Open Settings",
                            onClick = onOpenSettings,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                PermissionState.NEEDS_SETTINGS -> {
                    CloudCapsuleButton(
                        text = if (handler.id == "accessibility") "Enable Accessibility" else "Open Settings",
                        onClick = onOpenSettings,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                PermissionState.NOT_REQUIRED, PermissionState.UNAVAILABLE -> Unit
            }
        }
    }
}

/**
 * Informational row for gates that genuinely don't apply on this device —
 * shown so the user sees why nothing was asked, without any request button.
 */
@Composable
private fun NotRequiredCard(handler: PermissionHandler) {
    CloudGlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBox(handler.id, small = true)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = handler.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.ledger.deskPaper
                    )
                )
                Text(
                    text = handler.explanation,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.ledger.deskInkFaint,
                        lineHeight = 15.sp
                    )
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            CloudChip(
                text = "Not required",
                accentColor = MaterialTheme.ledger.deskInkFaint
            )
        }
    }
}

/** Small-caps ledger label above a group of cards. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            color = MaterialTheme.ledger.deskInk,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp
        ),
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

/** The live status chip (✓ Granted / ○ Not enabled / ⚠ Blocked / …). */
@Composable
private fun StatusChip(state: PermissionState) {
    val (text, color) = when (state) {
        PermissionState.GRANTED -> "✓ Granted" to MaterialTheme.ledger.lampDeep
        PermissionState.DENIED -> "○ Not enabled" to MaterialTheme.ledger.lampAmber
        PermissionState.PERMANENTLY_DENIED -> "⚠ Blocked" to MaterialTheme.ledger.emberRed
        PermissionState.NEEDS_SETTINGS -> "○ Needs settings" to MaterialTheme.ledger.lampAmber
        PermissionState.NOT_REQUIRED -> "Not required" to MaterialTheme.ledger.deskInkFaint
        PermissionState.UNAVAILABLE -> "Unavailable" to MaterialTheme.ledger.deskInkFaint
    }
    CloudChip(text = text, accentColor = color)
}

/** Feature glyph in a soft terracotta well. */
@Composable
private fun IconBox(id: String, small: Boolean = false) {
    val size = if (small) 38.dp else 44.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.ledger.lampHalo.copy(alpha = 0.28f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = iconFor(id),
            contentDescription = null,
            tint = MaterialTheme.ledger.lampDeep,
            modifier = Modifier.size(if (small) 20.dp else 24.dp)
        )
    }
}

private fun iconFor(id: String): ImageVector = when (id) {
    "voice_assistant" -> Icons.Filled.Mic
    "notifications" -> Icons.Filled.Notifications
    "accessibility" -> Icons.Filled.TouchApp
    "contacts" -> Icons.Filled.Contacts
    "sms" -> Icons.Filled.Sms
    "calendar" -> Icons.Filled.Event
    "camera" -> Icons.Filled.PhotoCamera
    "location" -> Icons.Filled.Place
    "bluetooth" -> Icons.Filled.Bluetooth
    "alarms" -> Icons.Filled.Alarm
    else -> Icons.Filled.Notifications
}

/** Resolves the hosting [Activity] from any context, or null when none exists. */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
