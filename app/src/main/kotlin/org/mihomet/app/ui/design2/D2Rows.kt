package org.mihomet.app.ui.design2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import org.mihomet.app.ui.components.rememberPressScale

/**
 * The standard interactive row of the language: a squircle icon container on
 * a tone step up, loud title, quiet subtitle, trailing slot. Rows are meant
 * to sit inside a [D2Panel]; the panel owns the container look, the row owns
 * the content and the press squeeze.
 */
@Composable
fun D2Row(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconContainer: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val interaction = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interaction, pressedScale = 0.98f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .scale(pressScale)
            .clip(D2.Inner)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction) { onClick() }
                } else {
                    Modifier
                }
            )
            .padding(vertical = 8.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(D2.IconBox)
                .clip(D2.Inner)
                .background(iconContainer)
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        trailing()
    }
}

/** Switch variant of [D2Row]: the whole row is tappable. */
@Composable
fun D2SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    iconContainer: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    iconTint: Color = MaterialTheme.colorScheme.primary
) {
    D2Row(
        icon = icon,
        title = title,
        subtitle = subtitle,
        iconContainer = iconContainer,
        iconTint = iconTint,
        onClick = { onCheckedChange(!checked) },
        modifier = modifier
    ) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Quiet caption text (16sp-line secondary info under blocks). */
@Composable
fun D2Caption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** Fixed vertical space of the language's rhythm tokens. */
@Composable
fun D2Gap(height: androidx.compose.ui.unit.Dp = D2.BlockGap) {
    Spacer(modifier = Modifier.height(height))
}
