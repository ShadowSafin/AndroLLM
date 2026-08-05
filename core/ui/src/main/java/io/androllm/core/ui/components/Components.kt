package io.androllm.core.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * Backward compatible wrapper for the desk background.
 */
@Composable
fun GradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    CloudAtmosphericBackground(modifier = modifier, content = content)
}

/**
 * A walnut desk panel — the writing surface of the desk. Soft offset shadow,
 * hairline border, warm wood; pressed states settle the card a fraction.
 */
@Composable
fun CloudGlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    borderColor: Color = DeskHairline,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "cardScale"
    )

    Surface(
        modifier = modifier
            .scale(scale)
            .shadow(
                elevation = 10.dp,
                shape = DeskCardShape,
                ambientColor = Color(0x66000000),
                spotColor = Color(0x88000000)
            )
            .pointerInput(onClick) {
                if (onClick != null) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        },
                        onTap = { onClick() }
                    )
                }
            },
        shape = DeskCardShape,
        color = DeskWalnut,
        border = BorderStroke(1.dp, if (isPressed) LampAmber.copy(alpha = 0.5f) else borderColor)
    ) {
        Box(
            modifier = Modifier.padding(contentPadding)
        ) {
            content()
        }
    }
}

/**
 * The lamp button — the one amber control on the desk. Press compresses; the
 * disabled state returns to quiet walnut with ink.
 */
@Composable
fun CloudCapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    gradient: androidx.compose.ui.graphics.Brush? = null
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "buttonScale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .clip(DeskPillShape)
            .background(
                when {
                    !enabled -> DeskHairlineSoft
                    isPressed -> LampAmber.copy(alpha = 0.85f)
                    else -> LampAmber
                }
            )
            .shadow(
                elevation = if (enabled) 6.dp else 0.dp,
                shape = DeskPillShape,
                ambientColor = Color(0x55000000),
                spotColor = LampAmber.copy(alpha = 0.25f)
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) InkOnLamp else DeskInkFaint,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) InkOnLamp else DeskInk.copy(alpha = 0.6f)
                )
            )
        }
    }
}

/**
 * BrandButton implementation mapped to the lamp button for compatibility.
 */
@Composable
fun BrandButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    CloudCapsuleButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled
    )
}

/**
 * SectionCard mapped to the walnut panel for compatibility.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    CloudGlassCard(modifier = modifier, content = content)
}

/**
 * A ruled chip — small caps label on a hairline, amber when live.
 */
@Composable
fun CloudChip(
    text: String,
    modifier: Modifier = Modifier,
    accentColor: Color = LampAmber,
    icon: ImageVector? = null
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = accentColor.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = accentColor
                )
            )
        }
    }
}

/**
 * A section heading: the serif voice and a small-caps ledger caption beneath.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(
                    color = DeskPaper
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = subtitle.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 1.4.sp,
                        color = DeskInk
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(12.dp))
            trailing()
        }
    }
}

/**
 * The desk's empty state: a blank sheet on the walnut.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null
) {
    CloudGlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentPadding = PaddingValues(32.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(DeskWalnutRaised)
                    .shadow(4.dp, CircleShape, ambientColor = Color(0x55000000)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = LampAmber,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    color = DeskPaper
                ),
                textAlign = TextAlign.Center
            )
            if (subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = DeskInk
                    ),
                    textAlign = TextAlign.Center
                )
            }
            if (action != null) {
                Spacer(modifier = Modifier.height(24.dp))
                action()
            }
        }
    }
}

/**
 * Centered amber loading ring.
 */
@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            color = LampAmber,
            trackColor = DeskHairline,
            strokeWidth = 3.dp,
            modifier = Modifier.padding(24.dp).size(36.dp)
        )
    }
}

/**
 * The wordmark: serif letters set in warm paper above the desk.
 */
@Composable
fun DeskWordmark(
    modifier: Modifier = Modifier,
    size: Dp = 30.dp
) {
    Text(
        text = "AndroLLM",
        modifier = modifier,
        style = MaterialTheme.typography.headlineMedium.copy(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold,
            fontSize = size.value.sp,
            color = DeskPaper
        )
    )
}

/**
 * The lit lamp dot — the single live point on a loaded model card. Breathes
 * slowly while active, sits dim when idle.
 */
@Composable
fun LampDot(
    modifier: Modifier = Modifier,
    lit: Boolean = true,
    size: Dp = 8.dp
) {
    val glow by animateFloatAsState(
        targetValue = if (lit) 1f else 0.35f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "lampGlow"
    )
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(LampHalo.copy(alpha = glow * 0.55f))
            .shadow(
                elevation = if (lit) 6.dp else 0.dp,
                shape = CircleShape,
                spotColor = LampAmber.copy(alpha = 0.5f)
            )
            .padding(2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(if (lit) LampGlow else DeskInkFaint)
        )
    }
}