package org.mihomet.app.ui.screens.profiles

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import org.mihomet.app.BuildConfig
import org.mihomet.app.R
import org.mihomet.app.core.config.WarningLocalizer
import org.mihomet.app.core.stringRes
import org.mihomet.app.ui.components.DiagnosticExportActions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversionReportSheet(diagnostic: ConversionDiagnostic, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val report = diagnostic.report
    val descriptions = diagnostic.safeWarnings.map { WarningLocalizer.localize(it, context) }
    val omitted = stringResource(R.string.diagnostic_detail_omitted)
    val failure = diagnostic.failureCode?.let { stringResource(it.stringRes(), omitted, omitted, omitted) }
    val export = remember(diagnostic, descriptions, failure) {
        buildConversionDiagnosticText(diagnostic, BuildConfig.VERSION_NAME, listOfNotNull(failure) + descriptions)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(stringResource(if (failure == null) R.string.conversion_report_title else R.string.conversion_report_failed), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                if (failure != null) Text(failure, color = MaterialTheme.colorScheme.error)
                if (report != null) {
                    Text(stringResource(R.string.conversion_report_nodes, report.importedNodes, report.sourceNodes), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.conversion_report_rules, report.importedRules, report.sourceRules))
                    if (report.duplicateNodes > 0) Text(stringResource(R.string.conversion_report_duplicates, report.duplicateNodes))
                }
                if (descriptions.isEmpty() && failure == null) Text(stringResource(R.string.conversion_report_complete), color = MaterialTheme.colorScheme.primary)
            }
            itemsIndexed(descriptions) { _, description ->
                ListItem(
                    headlineContent = { Text(description) },
                    leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                )
            }
            item { DiagnosticExportActions(export) }
            item { Text(stringResource(R.string.conversion_report_privacy), style = MaterialTheme.typography.bodySmall) }
            item {
                FilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    }
}
