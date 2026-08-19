package io.androllm.feature.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.androllm.core.permissions.PermissionState
import io.androllm.core.ui.components.CloudAtmosphericBackground
import timber.log.Timber
import io.androllm.core.ui.theme.ledger

/**
 * Settings → Permissions & Access — manage every permission/access gate after
 * setup. Uses the exact same handlers (and therefore the same live states and
 * actions) as the first-launch setup screen, so there is exactly one source
 * of truth for what each gate means.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsAccessScreen(
    onBack: () -> Unit,
    viewModel: PermissionsAccessViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = remember { context.findActivity() }

    // Bumped after a dialog result or after returning from system settings
    // (accessibility, app details, notification settings) → statuses re-read.
    var refreshTick by remember { mutableStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshTick++ }

    LifecycleResumeEffect(Unit) {
        refreshTick++
        onPauseOrDispose { }
    }

    // Handler set is a static Hilt multibinding — cache it so status reads
    // only recompute when a request result or settings return bumps refreshTick.
    val handlers = remember { viewModel.handlers }
    val states = remember(handlers, refreshTick) {
        handlers.associateWith { viewModel.status(it, activity) }
    }

    CloudAtmosphericBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = "Permissions & Access",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.ledger.deskPaper
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.ledger.deskPaper
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, top = 6.dp, bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = "Each row explains what the permission is used for. " +
                            "Nothing is required to use AndroLLM — enable only what you want.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.ledger.deskInk,
                            lineHeight = 17.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }

                if (handlers.isEmpty()) {
                    // Defensive: if the permission module was ever missed by
                    // Hilt aggregation the user gets a message, never a blank
                    // page. (Regression guard — see app/build.gradle.kts.)
                    item {
                        Text(
                            text = "No permission gates are available on this device.",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.ledger.deskInk
                            ),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp)
                        )
                    }
                } else {
                items(handlers, key = { it.id }) { handler ->
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
                                Timber.w("[Permissions] no system screen available for ${handler.id}")
                                refreshTick++
                            }
                        }
                    )
                }
                }

                item {
                    Box(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}
