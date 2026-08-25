package io.androllm.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.ui.theme.ledger
import kotlinx.coroutines.launch

enum class CloudTab(val route: String, val title: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Default.Home),
    CHAT("chat", "Chat", Icons.Default.Chat),
    MODELS("models", "Models", Icons.Default.Layers),
    PROFILE("profile", "Profile", Icons.Default.Person),
    SETTINGS("settings", "Settings", Icons.Default.Settings)
}

/**
 * The ledger dock — a floating frosted capsule with a smooth sliding pill on
 * the active tab. The pill animates between tabs with a spring, the active
 * icon lifts and warms to terracotta, and every label keeps the mono-caps
 * ledger hand. Consistent 22dp icons across the whole shell.
 */
@Composable
fun CloudBottomNavigationBar(
    currentRoute: String,
    onTabSelected: (CloudTab) -> Unit,
    modifier: Modifier = Modifier,
    badgeCountMap: Map<CloudTab, Int> = emptyMap()
) {
    val selectedIndex = CloudTab.entries.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
    val tabCount = CloudTab.entries.size

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .clip(RoundedCornerShape(34.dp))
                .background(MaterialTheme.ledger.deskWalnut.copy(alpha = 0.96f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.ledger.deskHairline,
                    shape = RoundedCornerShape(34.dp)
                )
                .padding(6.dp)
        ) {
            val tabWidth = maxWidth / tabCount
            val itemWidth = tabWidth
            val itemHeight = maxHeight - 12.dp
            val indicatorOffset by animateDpAsState(
                targetValue = tabWidth * selectedIndex,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                label = "navPillOffset"
            )

            // Sliding active-pill background.
            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .size(width = itemWidth, height = itemHeight)
                    .clip(RoundedCornerShape(26.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            )

            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CloudTab.entries.forEachIndexed { index, tab ->
                    val selected = index == selectedIndex
                    val scaleAnim = remember(tab) { Animatable(1f) }
                    val scope = rememberCoroutineScope()
                    val badgeCount = badgeCountMap[tab] ?: 0
                    val iconTint by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.ledger.deskInkFaint,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "navIconTint"
                    )
                    val labelColor by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.ledger.deskInk.copy(alpha = 0.7f),
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "navLabelTint"
                    )

                    Box(
                        modifier = Modifier
                            .size(width = itemWidth, height = itemHeight)
                            .clip(RoundedCornerShape(26.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                if (!selected) {
                                    scope.launch {
                                        scaleAnim.animateTo(0.85f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                        scaleAnim.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                    }
                                }
                                onTabSelected(tab)
                            },
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
                                    tint = iconTint,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .scale(scaleAnim.value)
                                        .graphicsLayer {
                                            translationY = if (selected) -1.dp.toPx() else 0f
                                        }
                                )
                                if (badgeCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (badgeCount > 9) "9+" else badgeCount.toString(),
                                            fontSize = 9.sp,
                                            color = MaterialTheme.ledger.inkOnLamp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(3.dp))

                            Text(
                                text = tab.title.uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    letterSpacing = 1.4.sp,
                                    fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium,
                                    color = labelColor
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}