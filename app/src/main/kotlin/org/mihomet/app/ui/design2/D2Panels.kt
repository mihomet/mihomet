package org.mihomet.app.ui.design2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mihomet.app.ui.components.rememberPressScale

/**
 * The filled tonal panel — the single container treatment of the language.
 * A tone step above the background, big radius, no outline, ever.
 */
@Composable
fun D2Panel(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape: androidx.compose.ui.graphics.Shape = D2.Container,
    contentPadding: PaddingValues = PaddingValues(D2.PanelPad),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(modifier = modifier, shape = shape, color = color) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * Section label — the quiet voice between loud panels: uppercase, wide
 * tracking, small, neutral tone.
 */
@Composable
fun D2SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.6.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 6.dp)
    )
}

/**
 * Screen header — a big black display title with tight tracking plus an
 * optional trailing slot for round tonal icon actions.
 */
@Composable
fun D2ScreenTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Black,
            letterSpacing = (-0.8).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        trailing()
    }
}

/**
 * Round tonal icon action for title rows: filled circle, no border, with a
 * springy press squeeze.
 */
@Composable
fun D2IconAction(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val interaction = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interaction, pressedScale = 0.9f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(42.dp)
            .scale(pressScale)
            .clip(D2.Pill)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(interactionSource = interaction) { onClick() }
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}
