package org.mihomet.app.ui.screens.connections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import org.mihomet.app.R
import org.mihomet.app.ui.components.CountBadge
import org.mihomet.app.ui.components.SearchPill
import org.mihomet.app.ui.theme.AccentBlue
import org.mihomet.app.ui.theme.AccentGreen
import org.mihomet.app.ui.theme.ExpressiveShapes

@OptIn(ExperimentalMaterial3Api::class)
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
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.nav_connections),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black
                        )
                        CountBadge(count = filteredConnections.size)
                    }
                },
                navigationIcon = {
                    Surface(
                        shape = ExpressiveShapes.Small,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.padding(start = 12.dp, end = 6.dp)
                    ) {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                actions = {
                    Surface(
                        shape = ExpressiveShapes.Small,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        IconButton(
                            onClick = { viewModel.closeAllConnections() },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = stringResource(R.string.cd_close_all_connections),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // Stats banner
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = ExpressiveShapes.Large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.connections_download_total), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(formatData(state.downloadTotal), fontWeight = FontWeight.Black, fontSize = 16.sp, color = AccentGreen)
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    )

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.connections_upload_total), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(formatData(state.uploadTotal), fontWeight = FontWeight.Black, fontSize = 16.sp, color = AccentBlue)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Capsule Search Bar
            SearchPill(
                value = state.searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                placeholderRes = R.string.connections_search_hint,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (filteredConnections.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.connections_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
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

@Composable
fun ExpressiveConnectionCard(
    item: org.mihomet.app.data.api.ConnectionItem,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ExpressiveShapes.Medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
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

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(32.dp)
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_close_connection),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
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
