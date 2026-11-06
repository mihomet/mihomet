package org.mihomet.app.ui.screens.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.mihomet.app.R
import org.mihomet.app.ui.components.CountBadge
import org.mihomet.app.ui.components.D2BackHeader
import org.mihomet.app.ui.components.ExpressiveSnackbarHost
import org.mihomet.app.ui.design2.D2
import org.mihomet.app.ui.design2.D2IconAction
import org.mihomet.app.ui.design2.D2Panel
import org.mihomet.app.ui.design2.D2SegmentOption
import org.mihomet.app.ui.design2.D2Segmented
import org.mihomet.app.ui.theme.AccentGreen
import org.mihomet.app.ui.theme.AccentOrange
import org.mihomet.app.ui.theme.AccentRed
import org.mihomet.app.ui.theme.AppHaptics
import org.mihomet.app.ui.theme.HapticType

/**
 * Logs on the Edition 2 language: big back-title header with round tonal
 * actions, a springy segmented level filter and the console as one filled
 * tonal panel — no outlined cards anywhere.
 */
@Composable
fun LogsScreen(
    onNavigateBack: () -> Unit,
    viewModel: LogsViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val filteredLogs = state.logs.filter {
        state.selectedLevel == "all" || it.type.equals(state.selectedLevel, ignoreCase = true)
    }

    val shareLogs: () -> Unit = {
        AppHaptics.perform(context, HapticType.CLICK, view)
        if (state.logs.isEmpty()) {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.logs_share_empty))
            }
        } else {
            scope.launch {
                val file = viewModel.exportLogs(context) ?: return@launch
                runCatching {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        // Chooser may restart the intent on some OEM builds —
                        // keep the read grant reachable via ClipData as well.
                        clipData = ClipData.newRawUri(file.name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(
                        Intent.createChooser(sendIntent, context.getString(R.string.logs_share))
                    )
                }
            }
        }
    }

    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { ExpressiveSnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
                .padding(horizontal = D2.ScreenH)
        ) {
            // ── Header: big display title + round tonal actions ──────────────
            D2BackHeader(
                title = stringResource(R.string.nav_logs),
                backContentDescription = stringResource(R.string.cd_back),
                onBack = onNavigateBack,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                CountBadge(count = filteredLogs.size)
                Spacer(modifier = Modifier.width(10.dp))
                D2IconAction(
                    icon = Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.cd_copy),
                    onClick = {
                        AppHaptics.perform(context, HapticType.CLICK, view)
                        val text = filteredLogs.joinToString("\n") { "[${it.type.uppercase()}] ${it.payload}" }
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Mihomet Logs", text))
                    }
                )
                Spacer(modifier = Modifier.width(10.dp))
                D2IconAction(
                    icon = Icons.Default.Share,
                    contentDescription = stringResource(R.string.logs_share),
                    onClick = shareLogs
                )
                Spacer(modifier = Modifier.width(10.dp))
                D2IconAction(
                    icon = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.cd_clear),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = {
                        AppHaptics.perform(context, HapticType.CLICK, view)
                        viewModel.clearLogs()
                    }
                )
            }

            // ── Level filter: springy segmented control ──────────────────────
            D2Segmented(
                options = listOf(
                    "all" to R.string.logs_level_all,
                    "info" to R.string.logs_level_info,
                    "warning" to R.string.logs_level_warning,
                    "error" to R.string.logs_level_error,
                    "debug" to R.string.logs_level_debug
                ).map { (level, titleRes) ->
                    D2SegmentOption(level, stringResource(titleRes))
                },
                selected = state.selectedLevel,
                onOptionSelected = { level ->
                    AppHaptics.perform(context, HapticType.CLICK, view)
                    viewModel.setLevel(level)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
            )

            // ── Log console: one filled tonal panel ──────────────────────────
            D2Panel(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .weight(1f),
                contentPadding = PaddingValues(14.dp)
            ) {
                if (filteredLogs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.logs_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredLogs) { log ->
                            val tagColor = when (log.type.lowercase()) {
                                "error" -> AccentRed
                                "warning" -> AccentOrange
                                "info" -> AccentGreen
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }

                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "[${log.type.uppercase()}] ",
                                    color = tagColor,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = log.payload,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
