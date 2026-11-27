package org.mihomet.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mihomet.app.ui.design2.D2IconAction

/** Separate, naturally measured rows keep actions and scaled titles from clipping. */
@Composable
fun D2BackHeader(
    title: String,
    backContentDescription: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            D2IconAction(Icons.AutoMirrored.Filled.ArrowBack, backContentDescription, onBack,
                tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            trailing()
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
    }
}
