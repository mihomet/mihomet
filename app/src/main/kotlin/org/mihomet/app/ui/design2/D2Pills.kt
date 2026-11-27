package org.mihomet.app.ui.design2

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.mihomet.app.R
import org.mihomet.app.ui.components.PulsingStatusDot

@Composable
fun D2StatusPill(text: String, accent: Color, modifier: Modifier = Modifier, pulsing: Boolean = false) {
    Surface(shape = D2.Pill, color = accent.copy(alpha = 0.14f), modifier = modifier) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            PulsingStatusDot(accent, size = 8.dp, pulsing = pulsing)
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = accent)
        }
    }
}

data class D2SegmentOption<T>(val value: T, val label: String)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> D2Segmented(
    options: List<D2SegmentOption<T>>, selected: T, onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    trackColor: Color = Color.Transparent,
    height: Dp = 48.dp
) {
    if (options.isEmpty()) return
    Surface(color = trackColor, modifier = modifier) {
        ButtonGroup(
            modifier = Modifier.fillMaxWidth().heightIn(min = height).selectableGroup(),
            overflowIndicator = { menu ->
                IconButton(onClick = { menu.show() }) {
                    Icon(Icons.Default.MoreHoriz, stringResource(R.string.cd_menu))
                }
            },
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { option ->
                toggleableItem(checked = selected == option.value, label = option.label,
                    onCheckedChange = { if (it) onOptionSelected(option.value) }, weight = 1f)
            }
        }
    }
}
