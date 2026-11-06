package org.mihomet.app.ui.design2

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mihomet.app.ui.components.PulsingStatusDot

/**
 * Filled status pill: tinted container (accent at low alpha), pulsing dot,
 * bold label. No border — tone does the work.
 */
@Composable
fun D2StatusPill(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false
) {
    Surface(shape = D2.Pill, color = accent.copy(alpha = 0.14f), modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            PulsingStatusDot(color = accent, size = 7.dp, pulsing = pulsing)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.8.sp,
                color = accent
            )
        }
    }
}

/** One selectable segment of [D2Segmented]. */
data class D2SegmentOption<T>(val value: T, val label: String)

/**
 * Expressive segmented control: a filled track with a springy pill thumb
 * that slides between segments. The thumb is the only filled element —
 * selection is carried by tone and motion, not borders.
 */
@Composable
fun <T> D2Segmented(
    options: List<D2SegmentOption<T>>,
    selected: T,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    thumbColor: Color = MaterialTheme.colorScheme.primaryContainer,
    height: Dp = 46.dp
) {
    if (options.isEmpty()) return
    Surface(shape = D2.Pill, color = trackColor, modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.padding(4.dp).height(height)) {
            val segmentWidth = maxWidth / options.size
            val selectedIndex = options
                .indexOfFirst { it.value == selected }
                .coerceAtLeast(0)
            val thumbOffset by animateDpAsState(
                targetValue = segmentWidth * selectedIndex,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "d2SegmentThumb"
            )

            // Sliding thumb
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .width(segmentWidth)
                    .fillMaxHeight()
                    .clip(D2.Pill)
                    .background(thumbColor)
            )

            // Hit targets + labels
            Row(modifier = Modifier.fillMaxSize()) {
                options.forEach { option ->
                    val isSelected = option.value == selected
                    val interaction = remember { MutableInteractionSource() }
                    val labelColor by animateColorAsState(
                        targetValue = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "d2SegmentLabel"
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .width(segmentWidth)
                            .fillMaxHeight()
                            .clip(D2.Pill)
                            .clickable(interactionSource = interaction, indication = null) {
                                onOptionSelected(option.value)
                            }
                    ) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.SemiBold,
                            letterSpacing = 0.3.sp,
                            color = labelColor,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
