package org.mihomet.app.ui.design2

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun D2Row(
    icon: ImageVector, title: String, modifier: Modifier = Modifier, subtitle: String? = null,
    iconContainer: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    iconTint: Color = MaterialTheme.colorScheme.primary, onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodyMedium) } },
        leadingContent = {
            Surface(shape = D2.Inner, color = iconContainer) {
                Box(Modifier.size(D2.IconBox), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Icon(icon, null, tint = iconTint, modifier = Modifier.size(24.dp))
                }
            }
        },
        trailingContent = { Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, content = trailing) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    )
}

@Composable
fun D2SwitchRow(
    icon: ImageVector, title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    iconContainer: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    iconTint: Color = MaterialTheme.colorScheme.primary
) {
    D2Row(icon, title, modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        subtitle, iconContainer, iconTint) { Switch(checked = checked, onCheckedChange = null) }
}

@Composable
fun D2Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
fun D2Gap(height: Dp = D2.BlockGap) { Spacer(Modifier.height(height)) }
