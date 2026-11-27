package org.mihomet.app.ui.screens.profiles

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.mihomet.app.R
import org.mihomet.app.core.config.ConversionReport
import org.mihomet.app.core.config.ConversionWarning
import org.mihomet.app.core.config.WarningLocalizer
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversionReportSheet(report: ConversionReport, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(stringResource(R.string.conversion_report_title), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.conversion_report_nodes, report.importedNodes, report.sourceNodes), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.conversion_report_rules, report.importedRules, report.sourceRules))
                if (report.duplicateNodes > 0) Text(stringResource(R.string.conversion_report_duplicates, report.duplicateNodes))
                if (report.issues.isEmpty()) Text(stringResource(R.string.conversion_report_complete), color = MaterialTheme.colorScheme.primary)
            }
            itemsIndexed(report.issues) { _, issue ->
                ListItem(
                    headlineContent = { Text(WarningLocalizer.localize(ConversionWarning.XrayLoss(issue), context)) },
                    leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                )
            }
            item {
                FilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    }
}
