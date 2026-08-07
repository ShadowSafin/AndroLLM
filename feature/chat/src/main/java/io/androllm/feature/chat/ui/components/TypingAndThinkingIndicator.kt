package io.androllm.feature.chat.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.ui.components.LampDot
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.LampGlow
import kotlinx.coroutines.delay

/**
 * The desk in thought — shown while the model is forming its first words.
 *
 * A lit lamp dot, a quietly rotating status line (Thinking…, Searching
 * memory…, Running locally… / Querying cloud…), and three slow amber pulses.
 * The status cycles so the reader always knows the desk is alive, never stuck.
 */
@Composable
fun TypingAndThinkingIndicator(
    modifier: Modifier = Modifier,
    statusText: String = "Thinking…",
    cloudMode: Boolean = false,
    memoryEnabled: Boolean = false
) {
    val localStatuses = buildList {
        add(statusText)
        if (memoryEnabled) add("Searching memory…")
        add("Preparing answer…")
        add("Running locally…")
    }.distinct()
    val cloudStatuses = buildList {
        add(statusText)
        add("Querying cloud…")
        add("Calling tools…")
        add("Streaming response…")
    }.distinct()

    val statuses = if (cloudMode) cloudStatuses else localStatuses
    var index by remember(statuses.size) { mutableIntStateOf(0) }

    LaunchedEffect(statuses.size) {
        while (true) {
            delay(1800)
            index = (index + 1) % statuses.size
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LampDot(size = 9.dp, lit = true)

        Spacer(modifier = Modifier.width(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AnimatedContent(
                targetState = statuses[index],
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                label = "thinking status"
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 0.8.sp,
                        color = DeskInk
                    )
                )
            }
            LampPulsingDots()
        }
    }
}

@Composable
private fun LampPulsingDots() {
    val dots = listOf(
        remember { Animatable(0.35f) },
        remember { Animatable(0.35f) },
        remember { Animatable(0.35f) }
    )

    dots.forEachIndexed { index, animatable ->
        LaunchedEffect(animatable) {
            delay(index * 160L)
            animatable.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 650, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        dots.forEach { animatable ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .scale(animatable.value)
                    .clip(CircleShape)
                    .background(LampGlow.copy(alpha = 0.5f + animatable.value * 0.5f))
            )
        }
    }
}
