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
 * Expressive protocol pill badge with dedicated distinct color palette.
 */
@Composable
fun ProtocolBadge(
    protocol: String,
    modifier: Modifier = Modifier
) {
    val cleanProto = protocol.uppercase()
    val (textColor, bgColor) = when {
        cleanProto.contains("VLESS") -> ProtocolColors.VlessText to ProtocolColors.VlessBg
        cleanProto.contains("HYSTERIA") || cleanProto.contains("HY2") -> ProtocolColors.HysteriaText to ProtocolColors.HysteriaBg
        cleanProto.contains("TROJAN") -> ProtocolColors.TrojanText to ProtocolColors.TrojanBg
        cleanProto.contains("SS") || cleanProto.contains("SHADOWSOCKS") -> ProtocolColors.ShadowsocksText to ProtocolColors.ShadowsocksBg
        cleanProto.contains("TUIC") -> ProtocolColors.TuicText to ProtocolColors.TuicBg
        cleanProto.contains("DIRECT") -> ProtocolColors.DirectText to ProtocolColors.DirectBg
        else -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
    }

    val displayLabel = when {
        cleanProto.contains("HYSTERIA2") || cleanProto.contains("HY2") -> "HY2"
        cleanProto.contains("HYSTERIA") -> "HY1"
        cleanProto.contains("SHADOWSOCKS") -> "SS"
        else -> cleanProto.take(6)
    }

    Surface(
        shape = ExpressiveShapes.Small,
        color = bgColor,
        modifier = modifier
    ) {
        Text(
            text = displayLabel,
            color = textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp)
        )
    }
}

/**
 * Expressive latency badge pill with live colored dot and numeric readout.
 */
@Composable
fun ExpressiveLatencyBadge(
    delayMs: Long,
    modifier: Modifier = Modifier
) {
    val (dotColor, textColor) = when {
        delayMs > 0 && delayMs < 120 -> AccentGreen to AccentGreenLight
        delayMs in 120..280 -> AccentOrange to AccentOrange
        delayMs > 280 -> AccentRed to AccentRed
        delayMs < 0 -> AccentRed to AccentRed
        else -> Color.Gray to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = when {
        delayMs > 0 -> stringResource(R.string.latency_ms, delayMs)
        delayMs < 0 -> stringResource(R.string.latency_timeout)
        else -> "—"
    }

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, dotColor.copy(alpha = 0.3f)),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
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
}
