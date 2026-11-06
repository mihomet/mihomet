package org.mihomet.app.ui.screens.connections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import org.mihomet.app.R
import org.mihomet.app.ui.components.CountBadge
import org.mihomet.app.ui.components.D2BackHeader
import org.mihomet.app.ui.components.SearchPill
import org.mihomet.app.ui.design2.D2
import org.mihomet.app.ui.design2.D2IconAction
import org.mihomet.app.ui.design2.D2Panel
import org.mihomet.app.ui.design2.D2StatBlock
import org.mihomet.app.ui.theme.AccentBlue
import org.mihomet.app.ui.theme.AccentGreen

/**
 * Connections on the Edition 2 language: big back-title header with round
 * tonal actions, big-numeral traffic totals (D2StatBlock), filled tonal
 * connection panels — no outlined cards anywhere.
 */
@Composable
fun ConnectionsScreen(
    onNavigateBack: () -> Unit,
    viewModel: ConnectionsViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    // Poll the controller API only while the screen is visible
    DisposableEffect(Unit) {
        viewModel.startPolling()
        onDispose { viewModel.stopPolling() }
    }

    val filteredConnections = state.connections.filter {
        state.searchQuery.isBlank() ||
                it.metadata.host.contains(state.searchQuery, ignoreCase = true) ||
                it.metadata.destinationIP.contains(state.searchQuery, ignoreCase = true) ||
                it.metadata.process.contains(state.searchQuery, ignoreCase = true)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
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
                title = stringResource(R.string.nav_connections),
                backContentDescription = stringResource(R.string.cd_back),
                onBack = onNavigateBack,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                CountBadge(count = filteredConnections.size)
                Spacer(modifier = Modifier.width(10.dp))
                D2IconAction(
                    icon = Icons.Default.DeleteSweep,
                    contentDescription = stringResource(R.string.cd_close_all_connections),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = { viewModel.closeAllConnections() }
                )
            }

            // ── Traffic totals: two big-numeral stat blocks ──────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(D2.BlockGap),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                D2StatBlock(
                    label = stringResource(R.string.connections_download_total),
                    value = formatData(state.downloadTotal),
                    accent = AccentGreen,
                    modifier = Modifier.weight(1f)
                )
                D2StatBlock(
                    label = stringResource(R.string.connections_upload_total),
                    value = formatData(state.uploadTotal),
                    accent = AccentBlue,
                    modifier = Modifier.weight(1f)
                )
            }

            // ── Search ───────────────────────────────────────────────────────
            SearchPill(
                value = state.searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                placeholderRes = R.string.connections_search_hint,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
            )

            if (filteredConnections.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 24.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Text(stringResource(R.string.connections_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filteredConnections, key = { it.id }) { item ->
                        ExpressiveConnectionCard(
                            item = item,
                            onClose = { viewModel.closeConnection(item.id) }
                        )
                    }
                }
            }
        }
    }
}

/** One live connection: filled tonal panel, loud host, quiet mono details. */
@Composable
fun ExpressiveConnectionCard(
    item: org.mihomet.app.data.api.ConnectionItem,
    onClose: () -> Unit
) {
    D2Panel(contentPadding = PaddingValues(14.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val host = item.metadata.host.ifBlank { item.metadata.destinationIP }
                Text(
                    text = host,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "${item.metadata.network.uppercase()} • ${item.metadata.destinationIP}:${item.metadata.destinationPort} ${if (item.metadata.process.isNotBlank()) "• " + item.metadata.process else ""}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "↓ ${formatData(item.download)}  ↑ ${formatData(item.upload)}  •  ${item.chains.joinToString(" > ")}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            D2IconAction(
                icon = Icons.Default.Close,
                contentDescription = stringResource(R.string.cd_close_connection),
                onClick = onClose
            )
        }
    }
}

@Composable
private fun formatData(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> stringResource(R.string.data_gb, bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024 * 1024 -> stringResource(R.string.data_mb, bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> stringResource(R.string.data_kb, bytes / 1024.0)
        else -> stringResource(R.string.data_b, bytes)
    }
}
