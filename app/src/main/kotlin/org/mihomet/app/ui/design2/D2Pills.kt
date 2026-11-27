package org.mihomet.app.ui.design2

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
        FlowRow(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                ToggleButton(
                    checked = selected == option.value,
                    onCheckedChange = { if (it) onOptionSelected(option.value) },
                    modifier = Modifier.heightIn(min = height),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(option.label, modifier = Modifier.width(IntrinsicSize.Max), maxLines = 1, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
