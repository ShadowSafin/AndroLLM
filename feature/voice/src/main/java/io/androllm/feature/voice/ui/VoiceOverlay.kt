package io.androllm.feature.voice.ui

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.ui.components.CloudBugdroidLogo
import io.androllm.core.voice.VoiceSettingsStore
import io.androllm.core.voice.model.VoiceSettings
import io.androllm.feature.voice.VoiceAssistantController
import io.androllm.feature.voice.VoiceOverlayEvent
import io.androllm.feature.voice.VoicePhase
import io.androllm.feature.voice.VoiceUiState
import kotlin.math.abs
import kotlin.math.sin

/**
 * Full-screen Gemini Live-style voice overlay.
 *
 * Frosted-glass gradient backdrop, an animated AndroLLM mascot orb (waveform
 * ring while listening, ripple + glow while thinking, streaming dots while
 * generating, speaking pulse while TTS plays), live streaming transcript +
 * answer, a model chip, and Cancel / Mute / Keyboard / Close controls.
 *
 * The overlay is PURELY presentational: it reads [VoiceAssistantController]
 * state and emits [VoiceOverlayEvent]s — it never touches a provider, the
 * engine or TTS directly.
 */
@Composable
fun VoiceOverlay(
    controller: VoiceAssistantController,
    settingsStore: VoiceSettingsStore
) {
    val state by controller.state.collectAsState()
    val settings by settingsStore.settings.collectAsState(initial = VoiceSettings())
    val display = OverlayDisplay.from(state)

    // ── Glass background (aurora gradient + blur where supported) ──
    val scrimAlpha = settings.overlayTransparency.coerceIn(0.25f, 1f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0B12).copy(alpha = scrimAlpha))
    ) {
        AuroraBackground(
            color = display.color,
            speed = settings.animationSpeed,
            transparency = scrimAlpha
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top: status chip + elapsed timer
            Spacer(modifier = Modifier.height(28.dp))
            StatusHeader(display = display, state = state, speed = settings.animationSpeed)

            // Center: mascot orb
            Spacer(modifier = Modifier.weight(0.5f))
            MascotOrb(
                display = display,
                micLevel = state.micRms.coerceIn(0f, 1f),
                size = settings.overlaySize,
                speed = settings.animationSpeed
            )

            // ── Big NOW SPEAKING karaoke caption (unmissable) ──
            SpokenCaption(
                state = state,
                display = display,
                speed = settings.animationSpeed
            )

            Spacer(modifier = Modifier.weight(0.5f))

            // Transcript + streaming answer
            TranscriptBlock(state = state, display = display)

            // Live mic level meter (visible while listening / speaking)
            if (display.state == OverlayDisplayState.LISTENING ||
                display.state == OverlayDisplayState.SPEAKING
            ) {
                Spacer(modifier = Modifier.height(14.dp))
                AudioLevelMeter(
                    level = state.micRms.coerceIn(0f, 1f),
                    color = display.color,
                    speed = settings.animationSpeed
                )
            }
            Spacer(modifier = Modifier.height(20.dp))

            // Bottom controls
            ModelChip(provider = state.modelProvider, model = state.modelName)
            Spacer(modifier = Modifier.height(14.dp))
            ControlRow(
                state = state,
                display = display,
                onCancel = { controller.emitOverlayEvent(VoiceOverlayEvent.Cancel) },
                onToggleMute = { controller.emitOverlayEvent(VoiceOverlayEvent.ToggleMute) },
                onOpenChat = { controller.emitOverlayEvent(VoiceOverlayEvent.OpenChat) },
                onOpenConversation = { controller.emitOverlayEvent(VoiceOverlayEvent.OpenConversation) },
                onClose = { controller.emitOverlayEvent(VoiceOverlayEvent.Close) }
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ── Display model ────────────────────────────────────────────────────────────

/** The Gemini Live-style presentation states shown by the overlay. */
internal enum class OverlayDisplayState(
    val label: String,
    val emoji: String,
    val color: Color,
    val isTurnInProgress: Boolean
) {
    LISTENING("Start speaking", "🎤", Color(0xFF4FC3F7), true),
    TRANSCRIBING("Transcribing", "📝", Color(0xFF4DD0E1), true),
    THINKING("Thinking", "🧠", Color(0xFFB39DDB), true),
    GENERATING("Responding", "✨", Color(0xFFFFD54F), true),
    SPEAKING("Speaking", "🔊", Color(0xFF81C784), true),
    DONE("Done", "✅", Color(0xFF90A4AE), false),
    IDLE("Idle", "✅", Color(0xFF90A4AE), false);

    companion object {
        fun of(phase: VoicePhase): OverlayDisplayState = when (phase) {
            VoicePhase.RECEIVING_AUDIO, VoicePhase.LISTEN, VoicePhase.LISTENING,
            VoicePhase.WAKE_DETECTED, VoicePhase.WAKE, VoicePhase.RUNNING_INFERENCE -> LISTENING
            VoicePhase.STARTING_STT -> TRANSCRIBING
            VoicePhase.THINK -> THINKING
            VoicePhase.GENERATING -> GENERATING
            VoicePhase.SPEAK, VoicePhase.SPEAKING -> SPEAKING
            VoicePhase.DONE -> DONE
            VoicePhase.IDLE -> IDLE
        }
    }
}

/** Resolved overlay presentation snapshot for the current [VoiceUiState]. */
internal data class OverlayDisplay(
    val state: OverlayDisplayState,
    val color: Color,
    val showCancel: Boolean,
    val showOpenConversation: Boolean
) {
    companion object {
        fun from(s: VoiceUiState): OverlayDisplay {
            val phase = if (s.phase == VoicePhase.GENERATING && s.answerText.isNotBlank()) {
                // Tokens already streaming — flip to "Responding" immediately.
                OverlayDisplayState.GENERATING
            } else {
                OverlayDisplayState.of(s.phase)
            }
            val showCancel = s.turnActive && phase.isTurnInProgress
            val showOpenConversation = phase == OverlayDisplayState.DONE &&
                s.answerText.isNotBlank()
            return OverlayDisplay(
                state = phase,
                color = phase.color,
                showCancel = showCancel,
                showOpenConversation = showOpenConversation
            )
        }
    }
}

// ── Aurora glass background ──────────────────────────────────────────────────

@Composable
private fun AuroraBackground(color: Color, speed: Float, transparency: Float) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (9000 / speed).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "drift"
    )

    Box(Modifier.fillMaxSize()) {
        // Blur is only available on Android 12+; on older versions the same
        // translucent gradient still reads as frosted glass.
        val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Modifier.blur(60.dp)
        } else {
            Modifier
        }
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(blurModifier)
        ) {
            val w = size.width
            val h = size.height
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.28f * transparency), Color.Transparent),
                    center = Offset(w * (0.25f + drift * 0.2f), h * 0.25f),
                    radius = w * 0.6f
                )
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF7C5CFF).copy(alpha = 0.18f * transparency), Color.Transparent),
                    center = Offset(w * (0.75f - drift * 0.15f), h * 0.6f),
                    radius = w * 0.55f
                )
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF1B1F2E).copy(alpha = 0.5f * transparency), Color.Transparent),
                    center = Offset(w * 0.5f, h * 1.05f),
                    radius = w * 0.7f
                )
            )
        }
        // Hairline vignette for depth
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent, Color.Black.copy(alpha = 0.3f))
                )
            )
        }
    }
}

// ── Status header ────────────────────────────────────────────────────────────

@Composable
private fun StatusHeader(display: OverlayDisplay, state: VoiceUiState, speed: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            targetState = display.state,
            transitionSpec = {
                (fadeIn(tween(240)) + scaleIn(initialScale = 0.85f, animationSpec = tween(240)))
                    .togetherWith(fadeOut(tween(160)) + scaleOut(targetScale = 0.9f, animationSpec = tween(160)))
            },
            label = "status"
        ) { ds ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${ds.emoji}  ${ds.label}",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (ds == OverlayDisplayState.THINKING || ds == OverlayDisplayState.GENERATING) {
                    Spacer(modifier = Modifier.width(10.dp))
                    StreamingDots(color = ds.color, speed = speed)
                }
            }
        }

        // Elapsed timer chip (thinking / generating)
        AnimatedVisibility(
            visible = display.state == OverlayDisplayState.THINKING ||
                display.state == OverlayDisplayState.GENERATING,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f)
        ) {
            Row(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatElapsed(state.elapsedMs),
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun StreamingDots(color: Color, speed: Float) {
    val transition = rememberInfiniteTransition(label = "dots")
    fun dotSpec(delay: Int): InfiniteRepeatableSpec<Float> = infiniteRepeatable(
        animation = tween((420 / speed).toInt(), delayMillis = (delay / speed).toInt()),
        repeatMode = RepeatMode.Restart
    )
    val dots = listOf(
        transition.animateFloat(0f, 1f, dotSpec(0), label = "d0"),
        transition.animateFloat(0f, 1f, dotSpec(140), label = "d1"),
        transition.animateFloat(0f, 1f, dotSpec(280), label = "d2")
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        dots.forEachIndexed { i, anim ->
            val alpha = (1f - abs(anim.value * 2f - 1f)).coerceIn(0.15f, 1f)
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha))
            )
        }
    }
}

// ── Mascot orb ───────────────────────────────────────────────────────────────

@Composable
private fun MascotOrb(
    display: OverlayDisplay,
    micLevel: Float,
    size: Float,
    speed: Float
) {
    val baseSize = (240.dp * size).coerceIn(160.dp, 300.dp)
    val mascotSize = (118.dp * size).coerceIn(84.dp, 148.dp)

    // Speaking pulse (scale) while TTS plays
    val transition = rememberInfiniteTransition(label = "mascot")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (700 / speed).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    // Slow, gentle "breathing" while the assistant speaks — the logo inhales
    // and exhales over a ~2.6s cycle instead of pulsing, so it feels alive
    // for every word of the answer instead of blinking.
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (2600 / speed).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )
    // The mascot shrinks after the user finishes speaking: full size while
    // listening (mic on), then a smooth scale-down into a compact "thinking"
    // orb (≈45% of its listening size) while the model generates. It grows
    // back (with a speaking pulse) when TTS starts. This is the Gemini Live
    // "logo minimizes" moment the user asked for — driven purely by the
    // phase, animated by Compose.
    val stateScale = when (display.state) {
        OverlayDisplayState.LISTENING, OverlayDisplayState.TRANSCRIBING -> 1.0f
        OverlayDisplayState.THINKING -> 0.45f
        OverlayDisplayState.GENERATING -> 0.52f
        OverlayDisplayState.SPEAKING -> 0.93f + 0.07f * breath
        else -> 1.0f
    }
    // Slow "breathing" on top of the base scale while thinking — the mascot
    // feels alive during generation, not frozen.
    val idlePulse = when (display.state) {
        OverlayDisplayState.THINKING, OverlayDisplayState.GENERATING -> 0.03f * pulse
        else -> 0.02f * pulse
    }
    val targetScale = stateScale + idlePulse
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = (500 / speed).toInt()),
        label = "mascotScale"
    )
    // The minimized mascot drifts slightly upward toward the top-center while
    // thinking/generating (Gemini Live style) and settles back down when the
    // voice returns.
    val liftUp by animateFloatAsState(
        targetValue = if (display.state == OverlayDisplayState.THINKING ||
            display.state == OverlayDisplayState.GENERATING
        ) -40f else 0f,
        animationSpec = tween(durationMillis = (500 / speed).toInt()),
        label = "mascotLift"
    )

    Box(
        modifier = Modifier
            .size(baseSize)
            .offset(y = liftUp.dp),
        contentAlignment = Alignment.Center
    ) {
        // Glow behind the mascot
        val glowAlpha = when (display.state) {
            OverlayDisplayState.THINKING -> 0.45f + 0.35f * pulse
            OverlayDisplayState.GENERATING -> 0.4f + 0.3f * pulse
            OverlayDisplayState.SPEAKING -> 0.3f + 0.25f * breath
            else -> 0.25f
        }
        Box(
            modifier = Modifier
                .size(baseSize * 0.72f)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(display.color.copy(alpha = glowAlpha), Color.Transparent)
                    )
                )
        )

        // Expanding ripples while listening
        if (display.state == OverlayDisplayState.LISTENING) {
            RippleRing(color = display.color, speed = speed, micLevel = micLevel)
        }

        // Circular waveform ring (listening / speaking)
        WaveformRing(
            color = display.color,
            micLevel = if (display.state == OverlayDisplayState.LISTENING) micLevel
            else if (display.state == OverlayDisplayState.SPEAKING) 0.55f + 0.25f * pulse
            else 0.0f,
            speed = speed,
            size = baseSize
        )

        // Gradient ring while thinking
        if (display.state == OverlayDisplayState.THINKING) {
            GradientRing(color = display.color, speed = speed, size = baseSize)
        }

        // Orbiting particles while thinking / generating
        if (display.state == OverlayDisplayState.THINKING ||
            display.state == OverlayDisplayState.GENERATING
        ) {
            OrbitingParticles(color = display.color, speed = speed, size = baseSize)
        }

        // The mascot itself
        Box(
            modifier = Modifier.scale(scale),
            contentAlignment = Alignment.Center
        ) {
            CloudBugdroidLogo(size = mascotSize)
        }
    }
}

@Composable
private fun RippleRing(color: Color, speed: Float, micLevel: Float) {
    val transition = rememberInfiniteTransition(label = "ripple")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (1400 / speed).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple"
    )
    val progress2 by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (1400 / speed).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple2"
    )
    val amplitude = 0.5f + 0.5f * micLevel
    Canvas(Modifier.fillMaxSize()) {
        val canvasSize = this.size
        val center = Offset(canvasSize.width / 2, canvasSize.height / 2)
        val base = canvasSize.minDimension * 0.5f
        drawCircle(
            color = color.copy(alpha = (1f - progress) * 0.35f * amplitude),
            radius = base * (0.55f + progress * 0.5f),
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )
        drawCircle(
            color = color.copy(alpha = (1f - (progress2 - 0.5f).coerceIn(0f, 1f)) * 0.3f * amplitude),
            radius = base * (0.5f + (progress2 - 0.5f).coerceIn(0f, 1f) * 0.6f),
            center = center,
            style = Stroke(width = 1.5.dp.toPx())
        )
    }
}

@Composable
private fun WaveformRing(color: Color, micLevel: Float, speed: Float, size: androidx.compose.ui.unit.Dp) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (1200 / speed).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )
    Canvas(Modifier.fillMaxSize()) {
        val canvasSize = this.size
        val center = Offset(canvasSize.width / 2, canvasSize.height / 2)
        val radius = canvasSize.minDimension * 0.42f
        val bars = 36
        for (i in 0 until bars) {
            val angle = (i.toFloat() / bars) * (2 * Math.PI).toFloat()
            val envelope = 0.5f + 0.5f * abs(sin(angle * 3f + phase))
            val barLen = (4.dp.toPx() + 26.dp.toPx() * envelope * micLevel.coerceIn(0f, 1f))
            val x0 = center.x + kotlin.math.cos(angle.toDouble()).toFloat() * radius
            val y0 = center.y + kotlin.math.sin(angle.toDouble()).toFloat() * radius
            val x1 = center.x + kotlin.math.cos(angle.toDouble()).toFloat() * (radius + barLen)
            val y1 = center.y + kotlin.math.sin(angle.toDouble()).toFloat() * (radius + barLen)
            drawLine(
                color = color.copy(alpha = 0.55f + 0.45f * envelope),
                start = Offset(x0, y0),
                end = Offset(x1, y1),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun GradientRing(color: Color, speed: Float, size: androidx.compose.ui.unit.Dp) {
    val transition = rememberInfiniteTransition(label = "gradRing")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (2200 / speed).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    Canvas(Modifier.fillMaxSize()) {
        val canvasSize = this.size
        val center = Offset(canvasSize.width / 2, canvasSize.height / 2)
        val radius = canvasSize.minDimension * 0.36f
        val segments = 48
        for (i in 0 until segments) {
            val base = (i.toFloat() / segments) * (2 * Math.PI).toFloat() +
                (rotation * Math.PI / 180.0).toFloat()
            val a0 = base
            val a1 = base + (2 * Math.PI).toFloat() / segments
            val r0 = radius
            val r1 = radius + 2.5f.dp.toPx()
            val alpha = if (i % 6 < 3) 0.9f else 0.35f
            drawLine(
                color = color.copy(alpha = alpha),
                start = Offset(center.x + kotlin.math.cos(a0.toDouble()).toFloat() * r0, center.y + kotlin.math.sin(a0.toDouble()).toFloat() * r0),
                end = Offset(center.x + kotlin.math.cos(a1.toDouble()).toFloat() * r1, center.y + kotlin.math.sin(a1.toDouble()).toFloat() * r1),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun OrbitingParticles(color: Color, speed: Float, size: androidx.compose.ui.unit.Dp) {
    val transition = rememberInfiniteTransition(label = "particles")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (2600 / speed).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "particleAngle"
    )
    Canvas(Modifier.fillMaxSize()) {
        val canvasSize = this.size
        val center = Offset(canvasSize.width / 2, canvasSize.height / 2)
        val radius = canvasSize.minDimension * 0.46f
        repeat(7) { i ->
            val offset = (i.toFloat() / 7) * (2 * Math.PI).toFloat()
            val a = angle + offset
            val wobble = 0.9f + 0.1f * sin(a * 3f)
            val px = center.x + kotlin.math.cos(a.toDouble()).toFloat() * radius * wobble
            val py = center.y + kotlin.math.sin(a.toDouble()).toFloat() * radius * wobble
            val alpha = 0.25f + 0.4f * (0.5f + 0.5f * sin(a * 2f))
            drawCircle(
                color = color.copy(alpha = alpha.coerceIn(0.15f, 0.8f)),
                radius = 2.5f.dp.toPx() * (0.7f + 0.3f * wobble),
                center = Offset(px, py)
            )
        }
    }
}

// ── NOW SPEAKING karaoke caption ────────────────────────────────────────────

/**
 * Big Gemini Live-style "now speaking" caption: the sentence currently being
 * spoken, rendered large under the mascot, with the word being spoken right
 * now glowing in an accent pill. Unmissable — this is the word-level
 * highlight the user asked for, front and center in the overlay.
 */
@Composable
private fun SpokenCaption(state: VoiceUiState, display: OverlayDisplay, speed: Float) {
    val text = state.answerText
    val start = state.spokenStart
    val end = state.spokenEnd
    val active = display.state == OverlayDisplayState.SPEAKING &&
        start >= 0 && end > start && start < text.length && end <= text.length

    AnimatedVisibility(
        visible = active,
        enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.9f, animationSpec = tween(250)),
        exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.95f, animationSpec = tween(180))
    ) {
        if (active) {
            val chunk = text.substring(start, end)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Label chip
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(display.color.copy(alpha = 0.18f))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(display.color)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "NOW SPEAKING",
                        color = display.color,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))

                // The sentence itself, karaoke-highlighted word by word.
                AnimatedContent(
                    targetState = chunk,
                    transitionSpec = {
                        (fadeIn(tween(250)) + slideInVertically(
                            initialOffsetY = { it / 4 },
                            animationSpec = tween(250)
                        )).togetherWith(fadeOut(tween(150)))
                    },
                    label = "spokenChunk"
                ) { c ->
                    val words = c.split(Regex("\\s+")).filter { it.isNotBlank() }
                    val current = state.spokenWordIndex.coerceIn(0, words.lastIndex)
                    Text(
                        text = buildKaraokeString(
                            words = words,
                            currentIndex = current,
                            accent = display.color
                        ),
                        color = Color.White.copy(alpha = 0.95f),
                        fontSize = 21.sp,
                        lineHeight = 30.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Builds the karaoke AnnotatedString for one spoken sentence: words before
 * the current one dim to the accent, the current word is bright + bold with
 * a soft accent pill behind it, upcoming words stay white.
 */
private fun buildKaraokeString(words: List<String>, currentIndex: Int, accent: Color): androidx.compose.ui.text.AnnotatedString =
    buildAnnotatedString {
        words.forEachIndexed { i, word ->
            val style = when {
                i < currentIndex -> SpanStyle(color = accent.copy(alpha = 0.6f))
                i == currentIndex -> SpanStyle(
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    background = accent.copy(alpha = 0.28f)
                )
                else -> SpanStyle(color = Color.White.copy(alpha = 0.9f))
            }
            withStyle(style) { append(word) }
            if (i < words.lastIndex) append(" ")
        }
    }

// ── Transcript + answer ──────────────────────────────────────────────────────

@Composable
private fun TranscriptBlock(state: VoiceUiState, display: OverlayDisplay) {
    val hasTranscript = state.transcript.isNotBlank() || state.partialTranscript.isNotBlank()
    val hasAnswer = state.answerText.isNotBlank()
    AnimatedVisibility(
        visible = hasTranscript || hasAnswer,
        enter = fadeIn(animationSpec = tween(300)) + scaleIn(initialScale = 0.97f, animationSpec = tween(300)),
        exit = fadeOut(animationSpec = tween(200))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // User transcript
            val transcript = state.transcript.ifBlank { state.partialTranscript }
            if (transcript.isNotBlank()) {
                Text(
                    text = transcript,
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
            // Streaming assistant answer — blinking cursor while streaming +
            // karaoke word highlighting while TTS is speaking this sentence.
            if (state.answerText.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = buildAnswerText(
                        text = state.answerText,
                        display = display,
                        state = state,
                        streaming = display.state == OverlayDisplayState.GENERATING ||
                            display.state == OverlayDisplayState.SPEAKING
                    ),
                    color = Color.White.copy(alpha = 0.95f),
                    fontSize = 16.sp,
                    lineHeight = 23.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(display.color.copy(alpha = 0.08f))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

/**
 * Builds the answer's AnnotatedString: a blinking block cursor while the
 * answer is still streaming, plus Gemini Live-style karaoke word highlighting
 * while TTS is speaking the sentence at [state.spokenStart..state.spokenEnd).
 *
 * Words that have already been spoken light up with the accent color, the
 * word currently being spoken is bright + bold with a soft background, and
 * upcoming words stay white — so the text "reads along" with the voice.
 */
@Composable
private fun buildAnswerText(
    text: String,
    display: OverlayDisplay,
    state: VoiceUiState,
    streaming: Boolean
): androidx.compose.ui.text.AnnotatedString {
    val transition = rememberInfiniteTransition(label = "cursor")
    val visible by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 450, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorBlink"
    )
    val accent = display.color
    return buildAnnotatedString {
        append(text)

        // ── Karaoke highlight: the sentence currently being spoken. Only
        // while TTS is actually playing (Speaking state) — a stale range left
        // over from a finished chunk must not dim text during Generating. ──
        val speaking = display.state == OverlayDisplayState.SPEAKING
        val spokenStart = state.spokenStart
        val spokenEnd = state.spokenEnd
        val chunkActive = speaking &&
            spokenStart in 0..text.length && spokenEnd in spokenStart..text.length
        if (chunkActive) {
            // Word boundaries with absolute offsets inside the full answer.
            val words = mutableListOf<Pair<IntRange, Int>>() // (char range, word index)
            var i = spokenStart
            var wordIndex = 0
            while (i < spokenEnd) {
                while (i < spokenEnd && text[i].isWhitespace()) i++
                if (i >= spokenEnd) break
                val start = i
                while (i < spokenEnd && !text[i].isWhitespace()) i++
                words.add((start until i) to wordIndex)
                wordIndex++
            }
            val current = state.spokenWordIndex
            words.forEach { (range, idx) ->
                val style = when {
                    current >= 0 && idx < current -> SpanStyle(color = accent.copy(alpha = 0.55f))
                    current >= 0 && idx == current -> SpanStyle(
                        color = accent,
                        fontWeight = FontWeight.Bold,
                        background = accent.copy(alpha = 0.22f)
                    )
                    else -> SpanStyle(color = accent.copy(alpha = 0.35f))
                }
                addStyle(style, range.first, range.last + 1)
            }
        }

        // ── Blinking cursor while streaming ──
        if (streaming) {
            append(" ")
            withStyle(SpanStyle(color = accent.copy(alpha = visible))) {
                append("\u2588")
            }
        }
    }
}

// ── Live mic level meter ─────────────────────────────────────────────────────

@Composable
private fun AudioLevelMeter(level: Float, color: Color, speed: Float) {
    val transition = rememberInfiniteTransition(label = "meter")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (160 / speed).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "meterPhase"
    )
    val bars = 24
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(bars) { i ->
            val wave = 0.5f + 0.5f * abs(sin(i * 0.9f + phase * 6.283f))
            val h = (6.dp + 22.dp * wave * level.coerceIn(0.05f, 1f))
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color.copy(alpha = 0.35f + 0.65f * wave))
            )
        }
    }
}

// ── Model chip ───────────────────────────────────────────────────────────────

@Composable
private fun ModelChip(provider: String, model: String) {
    AnimatedVisibility(
        visible = model.isNotBlank(),
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f)
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.1f))
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.SmartToy,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (provider.isNotBlank()) "$provider · $model" else model,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ── Controls ─────────────────────────────────────────────────────────────────

@Composable
private fun ControlRow(
    state: VoiceUiState,
    display: OverlayDisplay,
    onCancel: () -> Unit,
    onToggleMute: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenConversation: () -> Unit,
    onClose: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Open Conversation (finished turn)
        AnimatedVisibility(
            visible = display.showOpenConversation,
            enter = fadeIn() + scaleIn(initialScale = 0.92f),
            exit = fadeOut() + scaleOut(targetScale = 0.92f)
        ) {
            Button(
                onClick = onOpenConversation,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C5CFF)),
                shape = RoundedCornerShape(50)
            ) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Conversation", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }

        Row(
            modifier = Modifier.padding(top = if (display.showOpenConversation) 14.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cancel (in-flight turn)
            AnimatedVisibility(
                visible = display.showCancel,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                RoundIconButton(
                    icon = Icons.Filled.Close,
                    tint = Color(0xFFFF8A80),
                    background = Color(0x33FF8A80),
                    contentDescription = "Cancel",
                    onClick = onCancel
                )
            }
            // Mute
            RoundIconButton(
                icon = if (state.muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                tint = if (state.muted) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.9f),
                background = if (state.muted) Color(0x33FFD54F) else Color.White.copy(alpha = 0.12f),
                contentDescription = if (state.muted) "Unmute" else "Mute",
                onClick = onToggleMute
            )
            // Keyboard (open chat)
            RoundIconButton(
                icon = Icons.Filled.Keyboard,
                tint = Color.White.copy(alpha = 0.9f),
                background = Color.White.copy(alpha = 0.12f),
                contentDescription = "Open chat",
                onClick = onOpenChat
            )
            // Close
            RoundIconButton(
                icon = Icons.Filled.Close,
                tint = Color.White.copy(alpha = 0.9f),
                background = Color.White.copy(alpha = 0.12f),
                contentDescription = "Close assistant",
                onClick = onClose
            )
        }
    }
}

@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    background: Color,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = background,
        contentColor = tint
    ) {
        Box(
            modifier = Modifier.size(52.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun formatElapsed(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format(java.util.Locale.US, "%02d:%02d", m, s)
}


