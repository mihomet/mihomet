package org.mihomet.app.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mihomet.app.ui.components.rememberPressScale
import org.mihomet.app.ui.theme.ExpressiveShapes

/** Shared section-rhythm tokens: one icon-container treatment across all settings cards. */
internal val SettingsIconContainerSize: Dp = 40.dp
internal val SettingsIconSize: Dp = 20.dp

/** Shared in-card vertical rhythm for settings rows. */
internal val SettingsRowSpacing: Dp = 12.dp

/** Section heading above a settings panel — Edition 2 label treatment. */
@Composable
fun SettingsSectionTitle(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.6.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, bottom = 2.dp)
    )
}

/**
 * Shared animated selection indicator (node cards, mode-select rows):
 * one circle whose fill/border animate between states, with a springy check.
 */
@Composable
fun AnimatedSelectionIndicator(selected: Boolean, checkContentDescription: String?) {
    val indicatorColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "selectionFill"
    )
    val indicatorBorderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "selectionBorder"
    )
    val checkScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "selectionCheckScale"
    )

    Surface(
        shape = CircleShape,
        color = indicatorColor,
        border = BorderStroke(1.5.dp, indicatorBorderColor),
        modifier = Modifier.size(22.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.Check,
                contentDescription = checkContentDescription,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .size(14.dp)
                    .scale(checkScale)
            )
        }
    }
}

/** Selectable VPN / Root mode row with a check/radio indicator. */
@Composable
fun ExpressiveModeSelectRow(
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interactionSource, pressedScale = 0.98f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(pressScale)
            .clip(ExpressiveShapes.Medium)
            .clickable(interactionSource = interactionSource) { onSelect() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = ExpressiveShapes.Small,
            color = iconBg,
            modifier = Modifier.size(SettingsIconContainerSize)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(SettingsIconSize)
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Text(
                text = description,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        AnimatedSelectionIndicator(selected = selected, checkContentDescription = null)
    }
}

/** Settings row with a leading icon and a trailing [Switch]. The whole row is tappable. */
@Composable
fun ExpressiveSwitchRow(
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    // Edition 2 delegation: the visual language lives in the D2 kit now.
    org.mihomet.app.ui.design2.D2SwitchRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        checked = checked,
        onCheckedChange = onCheckedChange,
        iconContainer = iconBg,
        iconTint = iconTint
    )
}
