package io.androllm.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.androllm.core.common.UiState
import io.androllm.core.navigation.Routes
import io.androllm.core.ui.components.CloudAtmosphericBackground
import io.androllm.core.ui.components.CloudBottomNavigationBar
import io.androllm.core.ui.components.CloudBugdroidLogo
import io.androllm.core.ui.components.CloudCapsuleButton
import io.androllm.core.ui.components.CloudChip
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.components.CloudTab
import io.androllm.core.ui.components.RevolutPerformanceChartCard
import io.androllm.core.ui.components.RevolutResourceGaugeCard
import io.androllm.core.ui.components.SectionHeader
import io.androllm.core.ui.theme.AuroraCyan
import io.androllm.core.ui.theme.CloudWhite
import io.androllm.core.ui.theme.MoonSilver
import io.androllm.core.ui.theme.SkyBlue
import io.androllm.core.ui.theme.SoftCyan
import io.androllm.core.ui.theme.SunsetCloudPeach
import io.androllm.core.ui.components.PromptStudioCarousel
import io.androllm.feature.home.R
import io.androllm.feature.home.ui.components.ChatActivityCard

/**
 * Cloud Intelligence & Revolut-Inspired Super-App Home Screen.
 * Features live hardware gauges, performance waveform stream, prompt studio carousel, and activity cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    CloudAtmosphericBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    modifier = Modifier.statusBarsPadding(),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CloudBugdroidLogo(size = 38.dp, showMoon = false)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.home_title),
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = CloudWhite
                                    )
                                )
                                Text(
                                    text = "Intelligence Above The Clouds",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = SunsetCloudPeach,
                                        fontWeight = FontWeight.SemiBold
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
                                tint = MoonSilver
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            bottomBar = {
                CloudBottomNavigationBar(
                    currentRoute = Routes.HOME,
                    onTabSelected = { tab ->
                        if (tab.route != Routes.HOME) {
                            navController.navigate(tab.route)
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 1. Model Status Island
                item {
                    ModelStatusIsland(
                        isModelLoaded = (uiState as? UiState.Success)?.data?.isModelLoaded == true,
                        onManageModels = { navController.navigate(Routes.MODELS) }
                    )
                }

                // 2. Revolut Hardware & Resource Gauges
                item {
                    RevolutResourceGaugeCard(
                        ramUsedGb = 3.8f,
                        ramTotalGb = 8.0f,
                        tokensPerSecond = 24.5f,
                        vulkanEnabled = true
                    )
                }

                // 3. Revolut Live Performance Waveform Stream
                item {
                    RevolutPerformanceChartCard(
                        dataPoints = listOf(12f, 18f, 15f, 24f, 28f, 22f, 31f, 29f, 35f, 26f, 32f)
                    )
                }

                // 4. Floating Quick Actions Capsules
                item {
                    QuickActionsRow(
                        onNewChat = { navController.navigate(Routes.CHAT) },
                        onBrowseModels = { navController.navigate(Routes.MODELS) }
                    )
                }

                // 5. Revolut Prompt Studio Carousel
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

                // 6. Recent Conversations Activity Feed
                item {
                    SectionHeader(
                        title = "Activity & Chats",
                        subtitle = "Your private on-device conversations",
                        trailing = {
                            CloudChip(
                                text = "100% Offline",
                                accentColor = SoftCyan
                            )
                        }
                    )
                }

                val conversations = (uiState as? UiState.Success)?.data?.recentConversations ?: emptyList()
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
    isModelLoaded: Boolean,
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
                        text = if (isModelLoaded) "Vulkan Engine Ready" else "No Model Active",
                        accentColor = if (isModelLoaded) SoftCyan else AuroraCyan,
                        icon = Icons.Filled.Memory
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (isModelLoaded) "On-Device Inference Active" else "Select a GGUF Model to Begin",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = CloudWhite
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isModelLoaded) "Zero cloud dependency • Local RAM" else "Tap to explore model catalog & download",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MoonSilver.copy(alpha = 0.75f)
                    )
                )
            }
            Icon(
                imageVector = Icons.Filled.Psychology,
                contentDescription = null,
                tint = if (isModelLoaded) SkyBlue else MoonSilver.copy(alpha = 0.5f),
                modifier = Modifier.size(40.dp)
            )
        }
    }
}

@Composable
private fun QuickActionsRow(
    onNewChat: () -> Unit,
    onBrowseModels: () -> Unit
) {
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
            gradient = Brush.horizontalGradient(listOf(CloudWhite.copy(alpha = 0.15f), SkyBlue.copy(alpha = 0.25f))),
            modifier = Modifier.weight(1f)
        )
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
                    color = CloudWhite
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Start a new conversation with your local GGUF AI model",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MoonSilver.copy(alpha = 0.7f)
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
