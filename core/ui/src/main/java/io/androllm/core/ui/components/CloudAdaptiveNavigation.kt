package io.androllm.core.ui.components

import android.app.Activity
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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

    if (widthClass == WindowWidthSizeClass.Expanded || widthClass == WindowWidthSizeClass.Medium) {
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
 * Mirrors the bottom dock's styling vertically.
 */
@Composable
private fun CloudNavigationRail(
    currentRoute: String,
    onTabSelected: (CloudTab) -> Unit
) {
    Box(
        modifier = Modifier
            .width(96.dp)
            .fillMaxHeight()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(72.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(32.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xE6FBFAF4),
                            Color(0xE6ECEBE3)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            CloudGlassBorderHighlight,
                            CloudGlassBorder,
                            SunsetGlowAmber.copy(alpha = 0.3f)
                        )
                    ),
                    shape = RoundedCornerShape(32.dp)
                )
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceAround,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CloudTab.entries.forEach { tab ->
                val selected = currentRoute == tab.route
                val scaleAnim = remember { Animatable(1f) }
                val scope = rememberCoroutineScope()

                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (selected) LampDeep.copy(alpha = 0.14f) else Color.Transparent
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            scope.launch {
                                scaleAnim.animateTo(0.85f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                scaleAnim.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                            }
                            onTabSelected(tab)
                        }
                        .scale(scaleAnim.value)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.title,
                        tint = if (selected) LampAmber else DeskInkFaint,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = tab.title,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) DeskPaper else DeskInk.copy(alpha = 0.7f)
                        )
                    )
                }
            }
        }
    }
}
