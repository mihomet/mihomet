package org.mihomet.app.ui.design2

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun D2Panel(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape: androidx.compose.ui.graphics.Shape = D2.Container,
    contentPadding: PaddingValues = PaddingValues(D2.PanelPad),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(modifier = modifier, shape = shape, color = color) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun D2SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 8.dp, bottom = 4.dp))
}

@Composable
fun D2ScreenTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, content = trailing)
        Text(text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 4.dp))
    }
}

@Composable
fun D2IconAction(
    icon: ImageVector, contentDescription: String?, onClick: () -> Unit,
    modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    FilledTonalIconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(24.dp))
    }
}
