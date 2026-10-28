package org.mihomet.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mihomet.app.R
import org.mihomet.app.ui.theme.*

/**
 * Pulsing live status dot indicating connection or testing health.
 */
@Composable
fun PulsingStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
    pulsing: Boolean = true
) {
    if (pulsing) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulseDot")
        val scale by infiniteTransition.animateFloat(
            initialValue = 0.9f,
            targetValue = 1.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dotScale"
        )
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dotAlpha"
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.size(size * 2)
        ) {
            Box(
                modifier = Modifier
                    .size(size * 1.8f)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha * 0.35f))
            )
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(color)
        )
    }
}

/**
 * Expressive protocol pill badge.
 *
 * Monochrome and palette-agnostic: tinted with the current [MaterialTheme]
 * primary (soft tinted background + primary text) so it stays consistent in
 * the default, dynamic and AMOLED schemes. [ProtocolColors] is kept in
 * Color.kt only as a fallback palette for static previews.
 */
@Composable
fun ProtocolBadge(
    protocol: String,
    modifier: Modifier = Modifier
) {
    val cleanProto = protocol.uppercase()

    val displayLabel = when {
        cleanProto.contains("HYSTERIA2") || cleanProto.contains("HY2") -> "HY2"
        cleanProto.contains("HYSTERIA") -> "HY1"
        cleanProto.contains("SHADOWSOCKS") -> "SS"
        else -> cleanProto
    }

    Surface(
        shape = ExpressiveShapes.Small,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        modifier = modifier
    ) {
        Text(
            text = displayLabel,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp)
        )
    }
}

/**
 * Expressive latency badge pill with live colored dot and numeric readout.
 * Optionally renders a testing spinner and can be made clickable (e.g. to re-ping).
 */
@Composable
fun ExpressiveLatencyBadge(
    delayMs: Long,
    modifier: Modifier = Modifier,
    isTesting: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    // The badge sits on surfaceContainerHighest: in light themes the vivid accent
    // tones are too pale for text, so switch to their deep variants there.
    // Dark/AMOLED surfaces keep the vivid accents (badge bg stays a neutral
    // theme container, never an opaque dark chunk).
    val badgeSurfaceLuminance = MaterialTheme.colorScheme.surfaceContainerHighest.luminance()
    val lightSurface = badgeSurfaceLuminance > 0.5f
    val greenText = if (lightSurface) AccentGreenDeep else AccentGreenLight
    val orangeText = if (lightSurface) AccentOrangeDeep else AccentOrange
    val redText = if (lightSurface) AccentRedDeep else AccentRed

    val (dotColor, textColor) = when {
        isTesting -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primary
        delayMs > 0 && delayMs < 120 -> AccentGreen to greenText
        delayMs in 120..280 -> AccentOrange to orangeText
        delayMs > 280 -> AccentRed to redText
        delayMs < 0 -> AccentRed to redText
        else -> Color.Gray to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = when {
        isTesting -> "..."
        delayMs > 0 -> stringResource(R.string.latency_ms, delayMs)
        delayMs < 0 -> stringResource(R.string.latency_timeout)
        else -> "—"
    }

    val badgeContent: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        ) {
            if (isTesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = dotColor
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }

    if (onClick != null) {
        Surface(
            onClick = onClick,
            enabled = !isTesting,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(1.dp, dotColor.copy(alpha = 0.3f)),
            modifier = modifier
        ) {
            badgeContent()
        }
    } else {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(1.dp, dotColor.copy(alpha = 0.3f)),
            modifier = modifier
        ) {
            badgeContent()
        }
    }
}
