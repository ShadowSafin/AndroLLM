package io.androllm.feature.voice.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.androllm.feature.voice.VoiceAssistantController
import io.androllm.feature.voice.VoicePhase

/**
 * Floating assistant sheet — the "Listening… ██████ Transcript ██████
 * Thinking… ██████ Streaming Answer… ██████ Done" overlay. Never full screen:
 * a compact card pinned to the bottom of the screen.
 */
@Composable
fun VoiceOverlay(
    controller: VoiceAssistantController,
    onDismiss: () -> Unit
) {
    val state by controller.state.collectAsState()
    val surfaceColor = Color(0xFF14161C)
    val accent = Color(0xFF7C5CFF)

    Surface(
        color = surfaceColor.copy(alpha = 0.96f),
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 12.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (state.phase == VoicePhase.SPEAK) Icons.Filled.VolumeUp else Icons.Filled.Mic,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = statusText(state.phase),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    ),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close assistant",
                        tint = Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            if (state.phase == VoicePhase.LISTEN || state.phase == VoicePhase.SPEAK) {
                Spacer(modifier = Modifier.height(10.dp))
                WaveformBars(active = true, color = accent)
            }

            state.transcript.takeIf { it.isNotBlank() }?.let { transcript ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = transcript,
                    style = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            state.partialTranscript.takeIf { it.isNotBlank() }?.let { partial ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = partial,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Color.White.copy(alpha = 0.55f)
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            state.answerText.takeIf { it.isNotBlank() }?.let { answer ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = answer,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = Color.White.copy(alpha = 0.92f)
                    ),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            state.error?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Color(0xFFFF8A80)
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun statusText(phase: VoicePhase): String = when (phase) {
    VoicePhase.IDLE -> "Ready"
    VoicePhase.WAKE, VoicePhase.LISTENING -> "Listening for \u201CHey Andro\u201D\u2026"
    VoicePhase.LISTEN, VoicePhase.RECEIVING_AUDIO -> "Listening to speech\u2026"
    VoicePhase.RUNNING_INFERENCE -> "Processing wake word\u2026"
    VoicePhase.WAKE_DETECTED -> "Wake word detected!"
    VoicePhase.STARTING_STT -> "Starting speech recognition\u2026"
    VoicePhase.THINK, VoicePhase.GENERATING -> "Thinking\u2026"
    VoicePhase.SPEAK, VoicePhase.SPEAKING -> "Streaming answer\u2026"
    VoicePhase.DONE -> "Done"
}

/**
 * Animated equalizer bars shown while the assistant is listening or speaking.
 */
@Composable
private fun WaveformBars(active: Boolean, color: Color) {
    val transition = rememberInfiniteTransition(label = "waveform")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wave"
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
    ) {
        val barCount = 24
        val barWidth = size.width / barCount
        for (i in 0 until barCount) {
            val envelope = 0.25f + 0.75f * kotlin.math.abs(
                kotlin.math.sin(i * 0.9 + phase * Math.PI)
            ).toFloat()
            val barHeight = (size.height * 0.25f + size.height * 0.7f * envelope).toFloat()
            drawRoundRect(
                color = color.copy(alpha = 0.85f),
                topLeft = Offset(i * barWidth + barWidth * 0.25f, size.height - barHeight),
                size = Size(barWidth * 0.5f, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth * 0.25f)
            )
        }
    }
}
