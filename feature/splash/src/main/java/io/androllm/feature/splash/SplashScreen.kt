package io.androllm.feature.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.common.AppConstants
import io.androllm.core.ui.components.CloudAtmosphericBackground
import io.androllm.core.ui.components.CloudBugdroidLogo
import io.androllm.core.ui.components.rememberReduceMotion
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.LampDeep
import io.androllm.feature.splash.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Parchment Ledger Cinematic Splash.
 *
 * Choreography (never rushed):
 *   1. Atmospheric background is already breathing (parchment, terracotta glow).
 *   2. The bugdroid mark fades in while spring-scaling up.
 *   3. The mark settles into a slow, weightless floating bob.
 *   4. The tagline — "Private AI. Powered by You." — fades in beneath it.
 *   5. A quiet "Ink drying…" hint completes the composition.
 *   6. After the full beat, [onFinished] hands over to the entry flow.
 *
 * Reduce-motion: static logo, shortened timing.
 */
@Composable
fun SplashScreen(
    onFinished: () -> Unit
) {
    val reduceMotion = rememberReduceMotion()

    val logoAlpha = remember { Animatable(0f) }
    val logoScale = remember { Animatable(if (reduceMotion) 1f else 0.55f) }
    val taglineAlpha = remember { Animatable(0f) }
    val loadingAlpha = remember { Animatable(0f) }
    val bob = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // Continuous weightless float (skipped under reduce-motion).
        if (!reduceMotion) {
            launch {
                while (isActive) {
                    bob.animateTo(1f, tween(durationMillis = 1900, easing = FastOutSlowInEasing))
                    bob.animateTo(-1f, tween(durationMillis = 1900, easing = FastOutSlowInEasing))
                }
            }
        }

        // Entrance choreography (parallel where possible).
        launch { logoAlpha.animateTo(1f, tween(durationMillis = 800)) }
        launch {
            logoScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
        launch {
            delay(1200)
            taglineAlpha.animateTo(1f, tween(durationMillis = 700))
        }
        launch {
            delay(1700)
            loadingAlpha.animateTo(1f, tween(durationMillis = 500))
        }

        // Hold the composition for the full cinematic beat.
        delay(AppConstants.Animation.SPLASH_DURATION)
        delay(if (reduceMotion) 300 else 700)
        onFinished()
    }

    CloudAtmosphericBackground {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                // The Lamp & Bug Badge — the desk mark
                Box(
                    modifier = Modifier
                        .scale(logoScale.value)
                        .alpha(logoAlpha.value)
                        .graphicsLayer { translationY = bob.value * 7.dp.toPx() },
                    contentAlignment = Alignment.Center
                ) {
                    CloudBugdroidLogo(size = 180.dp)
                }

                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    text = "AndroLLM",
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = DeskPaper,
                        letterSpacing = 2.sp
                    ),
                    modifier = Modifier.alpha(taglineAlpha.value)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.splash_tagline),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = LampDeep,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp
                    ),
                    modifier = Modifier.alpha(taglineAlpha.value)
                )

                Spacer(modifier = Modifier.height(48.dp))

                Text(
                    text = stringResource(R.string.splash_loading),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = DeskInk,
                        letterSpacing = 1.5.sp
                    ),
                    modifier = Modifier.alpha(loadingAlpha.value)
                )
            }
        }
    }
}
