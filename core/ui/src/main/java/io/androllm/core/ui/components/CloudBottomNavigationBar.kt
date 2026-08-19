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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.ui.theme.DeskHairline
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.DeskWalnut
import io.androllm.core.ui.theme.DeskWalnutRaised
import io.androllm.core.ui.theme.InkOnLamp
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampGlow
import kotlinx.coroutines.launch
import io.androllm.core.ui.theme.ledger

enum class CloudTab(val route: String, val title: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Default.Home),
    CHAT("chat", "Chat", Icons.Default.Chat),
    MODELS("models", "Models", Icons.Default.Layers),
    PROFILE("profile", "Profile", Icons.Default.Person),
    SETTINGS("settings", "Settings", Icons.Default.Settings)
}

/**
 * The ledger dock — a parchment strip with mono-caps labels and a small
 * terracotta seal on the active tab. The active tab reads in ink with a
 * terracotta dot; every other tab is muted ink on cream.
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
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(66.dp)
                .clip(RoundedCornerShape(33.dp))
                .background(MaterialTheme.ledger.deskWalnut.copy(alpha = 0.96f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.ledger.deskHairline,
                    shape = RoundedCornerShape(33.dp)
                )
                .padding(horizontal = 6.dp)
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
                            .clip(RoundedCornerShape(24.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                scope.launch {
                                    scaleAnim.animateTo(0.9f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                    scaleAnim.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                }
                                onTabSelected(tab)
                            }
                            .scale(scaleAnim.value)
                            .padding(vertical = 6.dp),
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
                                    tint = if (selected) MaterialTheme.ledger.lampAmber else MaterialTheme.ledger.deskInkFaint,
                                    modifier = Modifier.size(20.dp)
                                )
                                if (badgeCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.ledger.lampAmber),
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
                                    letterSpacing = 1.6.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                    color = if (selected) MaterialTheme.ledger.deskPaper else MaterialTheme.ledger.deskInk.copy(alpha = 0.7f)
                                )
                            )

                            if (selected) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .size(3.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.ledger.lampGlow)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}