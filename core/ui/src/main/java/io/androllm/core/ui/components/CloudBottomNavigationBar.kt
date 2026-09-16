package io.androllm.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.ui.theme.AndroLLMTheme
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
 * The ledger dock — a floating frosted capsule where the active tab expands
 * into an icon + label pill while inactive tabs stay icon-only.
 *
 * A native port of the `bottom-nav-bar` pill pattern: spring entrance
 * (scale 0.9 + fade), per-item press squash (0.97), and a spring-driven label
 * reveal capped at 72dp with ellipsis. Icons stay at 22dp across the shell.
 *
 * Public API is unchanged ([currentRoute], [onTabSelected], [badgeCountMap]) so
 * every [CloudAdaptiveNavigation] host keeps working untouched.
 */
@Composable
fun CloudBottomNavigationBar(
    currentRoute: String,
    onTabSelected: (CloudTab) -> Unit,
    modifier: Modifier = Modifier,
    badgeCountMap: Map<CloudTab, Int> = emptyMap()
) {
    val selectedIndex = CloudTab.entries.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)

    // Entrance: scale 0.9 + fade -> 1, mirroring the spring pop of the source.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val enterScale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.9f,
        animationSpec = spring(stiffness = 300f, dampingRatio = 0.75f),
        label = "navEnterScale"
    )
    val enterAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = spring(stiffness = 300f, dampingRatio = 0.75f),
        label = "navEnterAlpha"
    )

    Box(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = enterScale
                    scaleY = enterScale
                    alpha = enterAlpha
                }
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.ledger.deskWalnut.copy(alpha = 0.96f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.ledger.deskHairline,
                    shape = RoundedCornerShape(50)
                )
                .selectableGroup()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CloudTab.entries.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                val pressScale = remember(tab) { Animatable(1f) }
                val scope = rememberCoroutineScope()
                val badgeCount = badgeCountMap[tab] ?: 0
                val pillColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0f)
                    },
                    animationSpec = spring(dampingRatio = 0.8f),
                    label = "navPillTint"
                )
                val iconTint by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.ledger.deskInkFaint
                    },
                    animationSpec = spring(dampingRatio = 0.8f),
                    label = "navIconTint"
                )
                val labelColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.ledger.deskInk.copy(alpha = 0.7f)
                    },
                    animationSpec = spring(dampingRatio = 0.8f),
                    label = "navLabelTint"
                )

                Row(
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .graphicsLayer {
                            scaleX = pressScale.value
                            scaleY = pressScale.value
                        }
                        .clip(RoundedCornerShape(50))
                        .background(pillColor)
                        .selectable(
                            selected = selected,
                            onClick = {
                                scope.launch {
                                    // whileTap squash, then release.
                                    pressScale.animateTo(0.97f, spring(dampingRatio = 0.8f))
                                    pressScale.animateTo(1f, spring(dampingRatio = 0.8f))
                                }
                                onTabSelected(tab)
                            },
                            role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        )
                        .padding(horizontal = if (selected) 14.dp else 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.title,
                            tint = iconTint,
                            modifier = Modifier.size(22.dp)
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

                    // Expanding label: 0 -> 72dp spring + fade, icon stays put.
                    AnimatedVisibility(
                        visible = selected,
                        enter = expandHorizontally(
                            animationSpec = spring(stiffness = 350f, dampingRatio = 0.8f),
                            expandFrom = Alignment.Start
                        ) + fadeIn(tween(durationMillis = 190)),
                        exit = shrinkHorizontally(
                            animationSpec = spring(stiffness = 350f, dampingRatio = 0.8f),
                            shrinkTowards = Alignment.Start
                        ) + fadeOut(tween(durationMillis = 190))
                    ) {
                        Text(
                            text = tab.title.uppercase(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                letterSpacing = 1.4.sp,
                                fontWeight = FontWeight.Bold,
                                color = labelColor
                            ),
                            modifier = Modifier
                                .widthIn(max = 72.dp)
                                .padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "Bottom nav — expanding pill", showBackground = true)
@Composable
private fun CloudBottomNavigationBarPreview() {
    AndroLLMTheme {
        CloudBottomNavigationBar(
            currentRoute = CloudTab.CHAT.route,
            onTabSelected = {},
            badgeCountMap = mapOf(CloudTab.CHAT to 3)
        )
    }
}
