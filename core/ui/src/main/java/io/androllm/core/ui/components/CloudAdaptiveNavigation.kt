package io.androllm.core.ui.components

import android.app.Activity
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.ui.theme.CloudGlassBorder
import io.androllm.core.ui.theme.CloudGlassBorderHighlight
import io.androllm.core.ui.theme.DeskHairline
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampDeep
import io.androllm.core.ui.theme.SunsetGlowAmber
import kotlinx.coroutines.launch
import io.androllm.core.ui.theme.ledger

/**
 * Adaptive app shell: renders the floating bottom dock on compact (phone)
 * widths and a floating glass navigation rail on medium/expanded (foldable,
 * tablet, desktop) widths. Content adapts to the selected layout.
 *
 * Drop-in replacement for `Scaffold(containerColor = Color.Transparent,
 * topBar = ..., bottomBar = { CloudBottomNavigationBar(...) })`.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun CloudAdaptiveNavigation(
    currentRoute: String,
    onTabSelected: (CloudTab) -> Unit,
    topBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    val activity = LocalContext.current as? Activity
    val widthClass = if (activity != null) {
        calculateWindowSizeClass(activity).widthSizeClass
    } else {
        WindowWidthSizeClass.Compact
    }

    if (widthClass == WindowWidthSizeClass.Expanded) {
        Row(modifier = Modifier.fillMaxSize()) {
            CloudNavigationRail(
                currentRoute = currentRoute,
                onTabSelected = onTabSelected
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                topBar()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    content(PaddingValues(0.dp))
                }
            }
        }
    } else {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = topBar,
            bottomBar = {
                CloudBottomNavigationBar(
                    currentRoute = currentRoute,
                    onTabSelected = onTabSelected
                )
            }
        ) { padding ->
            content(padding)
        }
    }
}

/**
 * Floating parchment navigation rail for medium & expanded window widths.
 * Mirrors the bottom dock's styling vertically with the same sliding pill.
 */
@Composable
private fun CloudNavigationRail(
    currentRoute: String,
    onTabSelected: (CloudTab) -> Unit
) {
    val selectedIndex = CloudTab.entries.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
    val tabCount = CloudTab.entries.size

    Box(
        modifier = Modifier
            .width(96.dp)
            .fillMaxHeight()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .width(72.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(32.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.ledger.cloudGlassSurface,
                            MaterialTheme.ledger.cloudGlassSurfaceVariant
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            MaterialTheme.ledger.cloudGlassBorderHighlight,
                            MaterialTheme.ledger.cloudGlassBorder,
                            MaterialTheme.ledger.sunsetGlowAmber.copy(alpha = 0.3f)
                        )
                    ),
                    shape = RoundedCornerShape(32.dp)
                )
                .padding(vertical = 12.dp)
        ) {
            val tabHeight = maxHeight / tabCount
            val itemWidth = maxWidth - 8.dp
            val itemHeight = tabHeight - 4.dp
            val indicatorOffset by animateDpAsState(
                targetValue = tabHeight * selectedIndex,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                label = "railPillOffset"
            )

            // Sliding active-pill background.
            Box(
                modifier = Modifier
                    .offset(x = 4.dp, y = indicatorOffset)
                    .size(width = itemWidth, height = itemHeight)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            )

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceAround,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CloudTab.entries.forEachIndexed { index, tab ->
                    val selected = index == selectedIndex
                    val scaleAnim = remember(tab) { Animatable(1f) }
                    val scope = rememberCoroutineScope()
                    val iconTint by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.ledger.deskInkFaint,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "railIconTint"
                    )
                    val labelColor by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.ledger.deskInk.copy(alpha = 0.7f),
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "railLabelTint"
                    )

                    Column(
                        modifier = Modifier
                            .size(width = itemWidth, height = itemHeight)
                            .clip(RoundedCornerShape(24.dp))
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
                            }
                            .scale(scaleAnim.value),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.title,
                            tint = iconTint,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = tab.title,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = labelColor
                            )
                        )
                    }
                }
            }
        }
    }
}
