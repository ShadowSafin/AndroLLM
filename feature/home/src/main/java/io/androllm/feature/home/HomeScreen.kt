package io.androllm.feature.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.androllm.core.common.UiState
import io.androllm.core.navigation.Routes
import io.androllm.core.ui.components.CloudAdaptiveNavigation
import io.androllm.core.ui.components.CloudAtmosphericBackground
import io.androllm.core.ui.components.CloudBugdroidLogo
import io.androllm.core.ui.components.CloudCapsuleButton
import io.androllm.core.ui.components.CloudChip
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.components.PromptStudioCarousel
import io.androllm.core.ui.components.RevolutPerformanceChartCard
import io.androllm.core.ui.components.RevolutResourceGaugeCard
import io.androllm.core.ui.components.SectionHeader
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.DeskPaperDim
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampDeep
import io.androllm.core.ui.theme.LampGlow
import io.androllm.core.utils.StorageUtils
import io.androllm.feature.home.R
import io.androllm.feature.home.ui.components.ChatActivityCard
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Parchment Ledger — Home. Every metric is real runtime telemetry from
 * [TelemetryRepository]: device RAM, engine tokens/sec, GPU/KV cache memory,
 * model storage, and engine lifecycle — all kept on the desk, on the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val data = (uiState as? UiState.Success)?.data ?: HomeData()

    CloudAtmosphericBackground {
        CloudAdaptiveNavigation(
            currentRoute = Routes.HOME,
            onTabSelected = { tab ->
                if (tab.route != Routes.HOME) {
                    navController.navigate(tab.route)
                }
            },
            topBar = {
                TopAppBar(
                    modifier = Modifier.statusBarsPadding(),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CloudBugdroidLogo(size = 30.dp, showMoon = false)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.home_title),
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = DeskPaper
                                    )
                                )
                                Text(
                                    text = greetingForTimeOfDay(),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = LampDeep,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 0.8.sp
                                    )
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.action_settings),
                                tint = DeskPaperDim
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 1. Model Status Island (real engine lifecycle)
                item {
                    ModelStatusIsland(
                        telemetry = telemetry,
                        onManageModels = { navController.navigate(Routes.MODELS) }
                    )
                }

                // 2. Live Hardware Gauges — REAL RAM + REAL tokens/sec from the engine
                item {
                    RevolutResourceGaugeCard(
                        ramUsedGb = telemetry.ramUsedMb / 1024f,
                        ramTotalGb = telemetry.ramTotalMb / 1024f,
                        tokensPerSecond = telemetry.tokensPerSecond,
                        vulkanEnabled = telemetry.vulkanSupported
                    )
                }

                // 3. Live Performance Waveform — REAL tokens/sec history from this session
                item {
                    RevolutPerformanceChartCard(
                        dataPoints = telemetry.speedHistory.ifEmpty { listOf(0f, 0f) },
                        subtitle = "Session tokens/sec — ${telemetry.speedHistory.size} samples"
                    )
                }

                // 4. Storage + GPU/KV Cache — REAL device & engine state
                item {
                    SystemStatusRow(telemetry = telemetry)
                }

                // 5. Quick Action Capsules — one amber, rest quiet
                item {
                    QuickActionsRow(
                        onNewChat = { navController.navigate(Routes.CHAT) },
                        onBrowseModels = { navController.navigate(Routes.MODELS) },
                        onDeveloperMode = { navController.navigate(Routes.DEVELOPER) },
                        onPromptStudio = { navController.navigate(Routes.PROMPTS) }
                    )
                }

                // 6. Prompt Studio Carousel
                item {
                    Column {
                        SectionHeader(
                            title = "Prompt Studio",
                            subtitle = "One-tap AI templates & presets"
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        PromptStudioCarousel(
                            onPromptSelected = { promptText ->
                                navController.navigate(Routes.CHAT)
                            }
                        )
                    }
                }

                // 7. Recent Conversations Activity Feed
                item {
                    SectionHeader(
                        title = "Activity & Chats",
                        subtitle = "Your private on-device conversations",
                        trailing = {
                    CloudChip(
                        text = "100% Offline",
                        accentColor = LampDeep
                    )
                        }
                    )
                }

                val conversations = data.recentConversations
                if (conversations.isEmpty()) {
                    item {
                        EmptyChatsIsland(
                            onStartChat = { navController.navigate(Routes.CHAT) }
                        )
                    }
                } else {
                    items(conversations, key = { it.id }) { conversation ->
                        ChatActivityCard(
                            conversation = conversation,
                            onClick = { navController.navigate(Routes.chatDetail(conversation.id)) },
                            onMenuClick = {}
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun ModelStatusIsland(
    telemetry: HomeTelemetry,
    onManageModels: () -> Unit
) {
    CloudGlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onManageModels
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CloudChip(
                        text = when {
                            telemetry.isGenerating -> "Generating…"
                            telemetry.isModelLoaded -> "Vulkan Engine Ready"
                            else -> "No Model Active"
                        },
                        accentColor = when {
                            telemetry.isGenerating -> LampDeep
                            telemetry.isModelLoaded -> LampDeep
                            else -> DeskInk
                        },
                        icon = Icons.Filled.Memory
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = when {
                        telemetry.isModelLoaded && telemetry.currentModelName.isNotBlank() ->
                            telemetry.currentModelName
                        telemetry.isModelLoaded -> "On-Device Inference Active"
                        else -> "Select a LiteRT Model to Begin"
                    },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = DeskPaper
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (telemetry.isModelLoaded) {
                        "Zero cloud dependency • ${telemetry.kvCacheMb.roundToInt()} MB KV cache resident"
                    } else {
                        "Tap to explore model catalog & download"
                    },
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = DeskInk
                    )
                )
            }
            Icon(
                imageVector = Icons.Filled.Psychology,
                contentDescription = null,
                tint = if (telemetry.isModelLoaded) LampGlow else DeskInkFaint,
                modifier = Modifier.size(40.dp)
            )
        }
    }
}

@Composable
private fun SystemStatusRow(telemetry: HomeTelemetry) {
    val storage = telemetry.deviceMetrics
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CloudGlassCard(modifier = Modifier.weight(1f)) {
            StatusMiniCard(
                title = "Model Storage",
                icon = Icons.Filled.Storage,
                value = if (storage != null) {
                    "${StorageUtils.formatBytes(storage.freeStorageBytes)} free"
                } else {
                    "—"
                },
                fraction = storage?.storageFreeFraction ?: 0f,
                accent = LampDeep
            )
        }
        CloudGlassCard(modifier = Modifier.weight(1f)) {
            StatusMiniCard(
                title = "GPU / KV Cache",
                icon = Icons.Filled.Speed,
                value = if (telemetry.isModelLoaded) {
                    "${telemetry.gpuMemoryMb.roundToInt()} MB GPU • ${telemetry.kvCacheMb.roundToInt()} MB KV"
                } else {
                    "Idle — load a model"
                },
                fraction = if (telemetry.isModelLoaded) 1f else 0f,
                accent = if (telemetry.vulkanSupported) LampGlow else LampDeep
            )
        }
    }
}

@Composable
private fun StatusMiniCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    fraction: Float,
    accent: Color
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = DeskInk
                )
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.SemiBold,
                color = DeskPaper
            )
        )
        Spacer(modifier = Modifier.height(10.dp))
        val animatedFraction by animateFloatAsState(
            targetValue = fraction.coerceIn(0f, 1f),
            label = "statusFraction"
        )
        LinearProgressIndicator(
            progress = { animatedFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = accent,
            trackColor = accent.copy(alpha = 0.15f)
        )
    }
}

@Composable
private fun QuickActionsRow(
    onNewChat: () -> Unit,
    onBrowseModels: () -> Unit,
    onDeveloperMode: () -> Unit,
    onPromptStudio: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CloudCapsuleButton(
                text = stringResource(R.string.action_new_chat),
                onClick = onNewChat,
                icon = Icons.Filled.Add,
                modifier = Modifier.weight(1.2f)
            )
            CloudCapsuleButton(
                text = stringResource(R.string.action_browse_models),
                onClick = onBrowseModels,
                gradient = Brush.horizontalGradient(listOf(LampGlow.copy(alpha = 0.2f), LampAmber.copy(alpha = 0.45f))),
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CloudCapsuleButton(
                text = "Developer Mode",
                onClick = onDeveloperMode,
                icon = Icons.Filled.Speed,
                gradient = Brush.horizontalGradient(listOf(LampGlow.copy(alpha = 0.35f), LampGlow.copy(alpha = 0.15f))),
                modifier = Modifier.weight(1f)
            )
            CloudCapsuleButton(
                text = "Prompt Studio",
                onClick = onPromptStudio,
                icon = Icons.Filled.Psychology,
                gradient = Brush.horizontalGradient(listOf(LampGlow.copy(alpha = 0.2f), LampDeep.copy(alpha = 0.35f))),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun EmptyChatsIsland(
    onStartChat: () -> Unit
) {
    CloudGlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onStartChat
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "No Conversations Yet",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = DeskPaper
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Start a new conversation with your local LiteRT AI model",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = DeskInk
                )
            )
            Spacer(modifier = Modifier.height(14.dp))
            CloudCapsuleButton(
                text = "Start First Chat",
                onClick = onStartChat,
                icon = Icons.Filled.Add
            )
        }
    }
}

private fun greetingForTimeOfDay(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Good morning — your private AI is ready"
        in 12..16 -> "Good afternoon — your private AI is ready"
        in 17..21 -> "Good evening — your private AI is ready"
        else -> "Good night — your private AI is ready"
    }
}
