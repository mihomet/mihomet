package org.mihomet.app.ui.components

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mihomet.app.R
import java.io.File
import java.io.Writer

@Composable
fun DiagnosticExportActions(text: String, sensitiveText: ((Writer) -> Unit)? = null) {
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.diagnostic_share)
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<((Writer) -> Unit)?>(null) }
    // Restore only the export kind; keep the source and its builder out of saved state.
    var pendingSourceExport by rememberSaveable { mutableStateOf(false) }
    var showSensitiveConfirmation by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        // A restored callback cannot prove that the current source is the one the user confirmed.
        val content: ((Writer) -> Unit)? = pendingExport ?: if (pendingSourceExport) null else ({ it.write(text) })
        pendingExport = null
        pendingSourceExport = false
        if (uri != null) {
            if (content == null) {
                failed = true
            } else scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use(content)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed = true
                }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = {
            failed = false
            pendingExport = { writer -> writer.write(text) }
            pendingSourceExport = false
            save.launch("mihomet-conversion-${System.currentTimeMillis()}.txt")
        }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
            Icon(Icons.Default.SaveAlt, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.diagnostic_save), textAlign = TextAlign.Center)
        }
        FilledTonalButton(onClick = {
            failed = false
            scope.launch {
                try {
                    val file = withContext(Dispatchers.IO) {
                        val directory = File(context.cacheDir, "conversion-reports").apply { mkdirs() }
                        File.createTempFile("conversion-", ".txt", directory).apply { writeText(text) }
                    }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri(file.name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, shareTitle))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed = true
                }
            }
        }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
            Icon(Icons.Default.Share, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.diagnostic_share), textAlign = TextAlign.Center)
        }
        if (sensitiveText != null) {
            OutlinedButton(
                onClick = { showSensitiveConfirmation = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
            ) {
                Icon(Icons.Outlined.Lock, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.diagnostic_export_source), textAlign = TextAlign.Center)
            }
        }
    }
    if (failed) Text(stringResource(R.string.diagnostic_export_failed), color = MaterialTheme.colorScheme.error)
    if (showSensitiveConfirmation && sensitiveText != null) {
        AlertDialog(
            onDismissRequest = { showSensitiveConfirmation = false },
            title = { Text(stringResource(R.string.diagnostic_source_title)) },
            text = { Text(stringResource(R.string.diagnostic_source_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    showSensitiveConfirmation = false
                    failed = false
                    pendingExport = sensitiveText
                    pendingSourceExport = true
                    save.launch("mihomet-source-${System.currentTimeMillis()}.txt")
                }) {
                    Text(stringResource(R.string.diagnostic_source_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSensitiveConfirmation = false }) {
                    Text(stringResource(R.string.diagnostic_source_cancel))
                }
            }
        )
    }
}
