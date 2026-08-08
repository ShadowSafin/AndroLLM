package io.androllm.core.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.androllm.core.ui.theme.DeskCardShape
import io.androllm.core.ui.theme.DeskHairline
import io.androllm.core.ui.theme.DeskHairlineSoft
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.DeskPillShape
import io.androllm.core.ui.theme.DeskWalnut
import io.androllm.core.ui.theme.DeskWalnutRaised
import io.androllm.core.ui.theme.InkOnLamp
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampGlow
import io.androllm.core.ui.theme.LampHalo
import kotlin.math.cos
import kotlin.math.sin

// ─────────────────────────────────────────────────────────────────────────────
// Provider glyphs (used by the authentication screen)
// ─────────────────────────────────────────────────────────────────────────────

private val GoogleBlue = Color(0xFF4285F4)
private val GoogleRed = Color(0xFFEA4335)
private val GoogleYellow = Color(0xFFFBBC05)
private val GoogleGreen = Color(0xFF34A853)

/**
 * Official Google "G" brand mark vector.
 */
val GoogleIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Google",
        defaultWidth = 48.dp,
        defaultHeight = 48.dp,
        viewportWidth = 48f,
        viewportHeight = 48f
    ).apply {
        addPath(
            pathData = addPathNodes("M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.66 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"),
            fill = SolidColor(GoogleRed)
        )
        addPath(
            pathData = addPathNodes("M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"),
            fill = SolidColor(GoogleBlue)
        )
        addPath(
            pathData = addPathNodes("M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.28-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24s.92 7.54 2.56 10.78l7.97-6.19z"),
            fill = SolidColor(GoogleYellow)
        )
        addPath(
            pathData = addPathNodes("M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.66 48 24 48z"),
            fill = SolidColor(GoogleGreen)
        )
    }.build()
}

/**
 * The four-color Google "G" mark.
 */
@Composable
fun GoogleGlyph(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp
) {
    androidx.compose.material3.Icon(
        imageVector = GoogleIcon,
        contentDescription = "Google",
        tint = Color.Unspecified,
        modifier = modifier.size(size)
    )
}

/**
 * Official GitHub octocat mark as a vector icon (tintable through [Icon]).
 */
val GitHubIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "GitHub",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        addPath(
            pathData = addPathNodes(
                "M12 0C5.37 0 0 5.37 0 12c0 5.306 3.435 9.799 8.205 11.387.6.113.82-.258.82-.577 " +
                    "0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7" +
                    "c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998" +
                    ".108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22" +
                    "-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405" +
                    "1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176" +
                    ".765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22" +
                    "0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 21.795 24 17.3 24 12c0-6.63-5.37-12-12-12z"
            ),
            fill = SolidColor(Color.White)
        )
    }.build()
}

// ─────────────────────────────────────────────────────────────────────────────
// Accent palette (profile setup) — warm family, one lamp at a time
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A user-selectable accent color.
 */
data class CloudAccent(
    val name: String,
    val argbHex: String
) {
    val color: Color get() = runCatching { Color(argbHex.toLong(16)) }.getOrDefault(LampAmber)
}

/**
 * The six accents offered during profile setup — all readings of the same
 * terracotta family: clay, ember, rose, umber, moss, slate.
 */
val CloudAccentOptions: List<CloudAccent> = listOf(
    CloudAccent("Terracotta", "FFD97757"),
    CloudAccent("Ember", "FFB3573E"),
    CloudAccent("Rose", "FFE0A489"),
    CloudAccent("Umber", "FF8C6A4E"),
    CloudAccent("Moss", "FF9AA86E"),
    CloudAccent("Slate", "FF8A9AA8")
)

/**
 * Gradient presets for the CloudAvatar — warm washes, not neon.
 */
val CloudAvatarGradients: List<List<Color>> = listOf(
    listOf(LampAmber, LampGlow),
    listOf(Color(0xFFB3573E), Color(0xFF8C3C2A)),
    listOf(Color(0xFFB08D6E), Color(0xFF8C6A4E)),
    listOf(Color(0xFF9AA86E), Color(0xFF7A8558)),
    listOf(Color(0xFF8A9AA8), Color(0xFF64717E)),
    listOf(Color(0xFFE0A489), Color(0xFFB3573E))
)

// ─────────────────────────────────────────────────────────────────────────────
// Scaffold & TopBar
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Full-bleed parchment scaffold for entry screens: the daylight background
 * with a transparent Material Scaffold layered on top.
 */
@Composable
fun CloudScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    CloudAtmosphericBackground(modifier = modifier) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = topBar,
            bottomBar = bottomBar,
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp)
        ) { padding ->
            content(padding)
        }
    }
}

/**
 * The parchment top bar — serif title, quiet back affordance.
 */
@Composable
fun CloudTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = DeskPaper
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                color = DeskPaper
            ),
            modifier = Modifier.weight(1f),
            textAlign = if (onBack != null) TextAlign.Start else TextAlign.Center
        )
        actions()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TextField & Avatar
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A ruled paper field — parchment pill with a cream hairline border and a
 * terracotta underline when the ink falls on it.
 */
@Composable
fun CloudTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label?.let { { Text(it, color = DeskInk) } },
        placeholder = placeholder?.let { { Text(it, color = DeskInkFaint) } },
        leadingIcon = leadingIcon?.let { { Icon(it, contentDescription = null, tint = LampAmber) } },
        singleLine = singleLine,
        enabled = enabled,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        shape = DeskPillShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = LampAmber,
            unfocusedBorderColor = DeskHairline,
            focusedTextColor = DeskPaper,
            unfocusedTextColor = DeskPaper,
            cursorColor = LampGlow,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledTextColor = DeskPaper.copy(alpha = 0.45f),
            focusedLabelColor = LampAmber,
            unfocusedLabelColor = DeskInk
        )
    )
}

/**
 * The desk avatar — a terracotta wash disc with warm paper initials.
 */
@Composable
fun CloudAvatar(
    modifier: Modifier = Modifier,
    index: Int = 0,
    initials: String = "",
    size: Dp = 64.dp
) {
    val gradient = CloudAvatarGradients[(index % CloudAvatarGradients.size + CloudAvatarGradients.size) % CloudAvatarGradients.size]
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(gradient)),
        contentAlignment = Alignment.Center
    ) {
        if (initials.isNotBlank()) {
            Text(
                text = initials.take(2).uppercase(),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = InkOnLamp
                )
            )
        } else {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = DeskPaper.copy(alpha = 0.85f),
                modifier = Modifier.size(size * 0.5f)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Motion & Section & Progress & Dialog
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Best-effort system reduce-motion detection (animator duration scale == 0).
 * Used to gate infinite animations on the splash and onboarding screens.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            android.provider.Settings.Global.getInt(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1
            ) == 0
        }.getOrDefault(false)
    }
}

/**
 * Grouped settings-style section with a quiet small-caps label.
 */
@Composable
fun CloudSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 1.6.sp,
                color = DeskInk
            )
        )
        Spacer(modifier = Modifier.height(10.dp))
        content()
    }
}

/**
 * Breathing terracotta ember loading indicator — the AndroLLM answer to a progress bar.
 */
@Composable
fun CloudProgress(
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    reduceMotion: Boolean = false
) {
    val transition = rememberInfiniteTransition(label = "CloudProgress")
    val pulse by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "lampPulse"
    )
    val glow by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "lampGlow"
    )

    val scale = if (reduceMotion) 1f else pulse
    Canvas(modifier = modifier.size(size)) {
        val base = size.toPx() / 2f
        // Ambient lamp halo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    LampAmber.copy(alpha = glow),
                    LampHalo.copy(alpha = glow * 0.4f),
                    Color.Transparent
                )
            ),
            radius = base * 1.2f,
            center = center
        )
        // The lamp shade — a tilted cone with the filament at its mouth.
        val shadeTop = androidx.compose.ui.geometry.Offset(center.x - base * 0.30f, center.y - base * 0.55f * scale)
        drawPath(
            path = androidx.compose.ui.graphics.Path().apply {
                moveTo(center.x - base * 0.42f, center.y - base * 0.52f)
                lineTo(center.x + base * 0.10f, center.y - base * 0.58f)
                lineTo(center.x + base * 0.22f, center.y - base * 0.05f)
                lineTo(center.x - base * 0.30f, center.y)
                close()
            },
            brush = Brush.verticalGradient(listOf(LampGlow.copy(alpha = 0.95f), LampAmber.copy(alpha = 0.9f)))
        )
        // The filament
        drawCircle(
            color = LampGlow.copy(alpha = 0.95f * glow * 2f),
            radius = base * 0.05f,
            center = androidx.compose.ui.geometry.Offset(center.x - base * 0.04f, center.y - base * 0.02f)
        )
        // Drifting motes of dust
        val angle = (pulse * 360f)
        for (i in 0 until 3) {
            val a = angle + i * 120f
            val sx = center.x + cos(Math.toRadians(a.toDouble())).toFloat() * base * 1.05f
            val sy = center.y + sin(Math.toRadians(a.toDouble())).toFloat() * base * 0.9f
            drawCircle(LampGlow.copy(alpha = 0.4f), radius = base * 0.045f, center = androidx.compose.ui.geometry.Offset(sx, sy))
        }
    }
}

/**
 * Parchment desk dialog with a title, body and confirm/cancel actions.
 */
@Composable
fun CloudDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmText: String = "OK",
    onConfirm: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier,
            shape = DeskCardShape,
            color = DeskWalnutRaised,
            border = BorderStroke(1.dp, DeskHairline),
            shadowElevation = 16.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = DeskPaper
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
                content()
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onConfirm != null) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel", color = DeskInk)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    TextButton(onClick = onConfirm ?: onDismiss) {
                        Text(
                            confirmText,
                            color = LampAmber,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}