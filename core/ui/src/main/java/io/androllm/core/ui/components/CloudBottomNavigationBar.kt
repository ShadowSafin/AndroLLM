package io.androllm.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.ui.theme.CloudGlassBorder
import io.androllm.core.ui.theme.CloudGlassBorderHighlight
import io.androllm.core.ui.theme.CloudGlassSurface
import io.androllm.core.ui.theme.CloudWhite
import io.androllm.core.ui.theme.MoonSilver
import io.androllm.core.ui.theme.RevolutNeonEmerald
import io.androllm.core.ui.theme.SkyBlue
import io.androllm.core.ui.theme.SunsetCloudOrange
import io.androllm.core.ui.theme.SunsetCloudPeach
import io.androllm.core.ui.theme.SunsetGlowAmber
import kotlinx.coroutines.launch

enum class CloudTab(val route: String, val title: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Default.Home),
    CHAT("chat", "Chat", Icons.Default.Chat),
    MODELS("models", "Models", Icons.Default.Layers),
    SETTINGS("settings", "Settings", Icons.Default.Settings)
}

/**
 * Revolut-Style Floating Glass Bottom Navigation Bar.
 * Features spring animated pill indicator, subtle sunset highlights, and badge counters.
 */
@Composable
fun CloudBottomNavigationBar(
    currentRoute: String,
    onTabSelected: (CloudTab) -> Unit,
    modifier: Modifier = Modifier,
    badgeCountMap: Map<CloudTab, Int> = emptyMap()
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        // Floating Glass Island Capsule
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .clip(RoundedCornerShape(34.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xDF0E1626),
                            Color(0xF5070B14)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        listOf(
                            CloudGlassBorderHighlight,
                            CloudGlassBorder,
                            SunsetGlowAmber.copy(alpha = 0.3f)
                        )
                    ),
                    shape = RoundedCornerShape(34.dp)
                )
                .padding(horizontal = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CloudTab.entries.forEach { tab ->
                    val selected = currentRoute == tab.route
                    val scaleAnim = remember { Animatable(1f) }
                    val scope = rememberCoroutineScope()
                    val badgeCount = badgeCountMap[tab] ?: 0

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(26.dp))
                            .background(
                                if (selected) SunsetCloudOrange.copy(alpha = 0.25f) else Color.Transparent
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                scope.launch {
                                    scaleAnim.animateTo(0.88f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                    scaleAnim.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                }
                                onTabSelected(tab)
                            }
                            .scale(scaleAnim.value)
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.title,
                                    tint = if (selected) SunsetCloudPeach else MoonSilver.copy(alpha = 0.6f),
                                    modifier = Modifier.size(22.dp)
                                )

                                if (badgeCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(RevolutNeonEmerald),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (badgeCount > 9) "9+" else badgeCount.toString(),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(3.dp))

                            Text(
                                text = tab.title,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) CloudWhite else MoonSilver.copy(alpha = 0.5f)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
