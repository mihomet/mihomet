package org.mihomet.app.ui.screens.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import org.mihomet.app.data.preferences.ProxyMode
import org.mihomet.app.ui.theme.AccentBlue
import org.mihomet.app.ui.theme.AccentGreen
import org.mihomet.app.ui.theme.AccentOrange
import org.mihomet.app.ui.theme.AccentRed
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToConnections: () -> Unit,
    onNavigateToLogs: () -> Unit,
    viewModel: DashboardViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Mihomet",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToConnections) {
                        Icon(Icons.Default.Hub, contentDescription = "Connections")
                    }
                    IconButton(onClick = onNavigateToLogs) {
                        Icon(Icons.Default.Terminal, contentDescription = "Logs")
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hero Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val statusColor by animateColorAsState(
                        targetValue = if (state.isRunning) AccentGreen else MaterialTheme.colorScheme.outline,
                        label = "statusColor"
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Text(
                            text = if (state.isRunning) "CONNECTED" else "DISCONNECTED",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = statusColor
                        )
                        SuggestionChip(
                            onClick = {},
                            label = {
                                Text(
                                    if (state.isRootMode) "ROOT TPROXY" else "VPN",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = if (state.isRootMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Big Power Button
                    FilledIconButton(
                        onClick = { viewModel.toggleProxy() },
                        modifier = Modifier.size(80.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (state.isRunning) AccentGreen else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            Icons.Default.PowerSettingsNew,
                            contentDescription = "Toggle Proxy",
                            modifier = Modifier.size(40.dp),
                            tint = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = state.activeProfileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Real-time Traffic Graph Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Traffic",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(AccentBlue)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "↓ ${formatSpeed(state.currentTraffic.down)}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = AccentBlue
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(AccentGreen)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "↑ ${formatSpeed(state.currentTraffic.up)}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = AccentGreen
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    TrafficGraph(history = state.trafficHistory)
                }
            }

            // Routing Mode Selector
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Routing Mode",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = state.activeMode == ProxyMode.RULE,
                            onClick = { viewModel.setMode(ProxyMode.RULE) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                        ) {
                            Text("Rule")
                        }
                        SegmentedButton(
                            selected = state.activeMode == ProxyMode.GLOBAL,
                            onClick = { viewModel.setMode(ProxyMode.GLOBAL) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                        ) {
                            Text("Global")
                        }
                        SegmentedButton(
                            selected = state.activeMode == ProxyMode.DIRECT,
                            onClick = { viewModel.setMode(ProxyMode.DIRECT) },
                            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                        ) {
                            Text("Direct")
                        }
                    }
                }
            }

            // Quick Active Node Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = state.primaryGroup,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = state.selectedNode,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    if (state.selectedNodeDelay > 0) {
                        val latencyColor = when {
                            state.selectedNodeDelay < 200 -> AccentGreen
                            state.selectedNodeDelay < 500 -> AccentOrange
                            else -> AccentRed
                        }
                        SuggestionChip(
                            onClick = { viewModel.loadProxyStatus() },
                            label = { Text("${state.selectedNodeDelay} ms", color = latencyColor) }
                        )
                    } else {
                        IconButton(onClick = { viewModel.loadProxyStatus() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun formatSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
        bytesPerSec >= 1024 -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
        else -> "$bytesPerSec B/s"
    }
}
