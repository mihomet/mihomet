package org.mihomet.app.ui.screens.dashboard

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.koin.androidx.compose.koinViewModel
import org.mihomet.app.R
import org.mihomet.app.core.ServiceState
import org.mihomet.app.data.preferences.ProxyMode
import org.mihomet.app.ui.components.ExpressiveLatencyBadge
import org.mihomet.app.ui.design2.*
import org.mihomet.app.ui.theme.*

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DashboardScreen(
    onNavigateToServers: () -> Unit,
    onNavigateToConnections: () -> Unit,
    onNavigateToLogs: () -> Unit,
    viewModel: DashboardViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) viewModel.startProxy()
    }
    // Keep the full-bleed hero; the app shell owns system and navigation insets.
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = D2.ScreenH),
        verticalArrangement = Arrangement.spacedBy(D2.SectionGap)
    ) {
        D2ScreenTitle(stringResource(R.string.app_name), Modifier.padding(top = 8.dp)) {
            D2IconAction(Icons.Default.Hub, stringResource(R.string.nav_connections), onNavigateToConnections)
            Spacer(Modifier.width(8.dp))
            D2IconAction(Icons.Default.Terminal, stringResource(R.string.nav_logs), onNavigateToLogs)
        }
        ConnectionHero(state) {
            AppHaptics.perform(context, HapticType.HEAVY, view)
            if (state.isRunning || state.isConnecting || state.isDisconnecting) viewModel.stopProxy()
            else if (state.isRootMode) viewModel.startProxy()
            else {
                val request = VpnService.prepare(context)
                if (request != null) permission.launch(request) else viewModel.startProxy()
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            D2SectionLabel(stringResource(R.string.dashboard_active_server))
            Surface(
                onClick = onNavigateToServers,
                shape = D2.Container,
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                ListItem(
                    headlineContent = { Text(state.selectedNode.ifBlank { stringResource(R.string.dashboard_select_server) }, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(state.activeProfileName.ifBlank { stringResource(R.string.dashboard_default_profile_name) }) },
                    leadingContent = { Icon(Icons.Default.Dns, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        if (state.selectedNodeDelay != 0L) ExpressiveLatencyBadge(state.selectedNodeDelay)
                        else Icon(Icons.Default.ChevronRight, null)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            D2StatBlock(stringResource(R.string.dashboard_download), formatSpeed(state.currentTraffic.down), Modifier.weight(1f), MaterialTheme.colorScheme.primary)
            D2StatBlock(stringResource(R.string.dashboard_upload), formatSpeed(state.currentTraffic.up), Modifier.weight(1f), MaterialTheme.colorScheme.tertiary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            D2SectionLabel(stringResource(R.string.dashboard_routing_mode))
            D2Segmented(
                options = listOf(
                    D2SegmentOption(ProxyMode.RULE, stringResource(R.string.mode_rule)),
                    D2SegmentOption(ProxyMode.GLOBAL, stringResource(R.string.mode_global)),
                    D2SegmentOption(ProxyMode.DIRECT, stringResource(R.string.mode_direct))
                ),
                selected = state.activeMode,
                onOptionSelected = { AppHaptics.perform(context, HapticType.CLICK, view); viewModel.setMode(it) },
                modifier = Modifier.fillMaxWidth()
            )
            D2Caption(stringResource(when (state.activeMode) {
                ProxyMode.RULE -> R.string.dashboard_mode_rule_desc
                ProxyMode.GLOBAL -> R.string.dashboard_mode_global_desc
                ProxyMode.DIRECT -> R.string.dashboard_mode_direct_desc
            }), Modifier.padding(horizontal = 8.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            D2SectionLabel(stringResource(R.string.dashboard_traffic_activity))
            D2Panel {
                TrafficGraph(state.trafficHistory, Modifier.fillMaxWidth().height(140.dp))
                if (state.isRunning) {
                    Spacer(Modifier.height(12.dp))
                    D2Caption(stringResource(R.string.dash_connections, state.connectionsCount) + " · " +
                        stringResource(R.string.dash_memory, stringResource(R.string.dash_memory_value, state.memoryInuse / 1048576.0)))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ConnectionHero(state: DashboardUiState, onToggle: () -> Unit) {
    val busy = state.isConnecting || state.isDisconnecting
    val status = stringResource(when {
        state.isDisconnecting -> R.string.dashboard_status_disconnecting
        state.isConnecting -> R.string.dashboard_status_connecting
        state.isRunning -> R.string.dashboard_status_protected
        else -> R.string.dashboard_status_disconnected
    })
    val action = stringResource(if (state.isRunning) R.string.action_disconnect else if (busy) R.string.action_cancel else R.string.cd_dashboard_connect)
    val container by animateColorAsState(when {
        busy -> MaterialTheme.colorScheme.primaryContainer
        state.isRunning -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }, animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(), label = "connectionColor")
    val content = when {
        busy -> MaterialTheme.colorScheme.onPrimaryContainer
        state.isRunning -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.primary
    }
    val progress = ExpressiveMorph.progress(if (state.isRunning) 1f else 0f)
    val start by ServiceState.connectionStartTime.collectAsStateWithLifecycle()
    var uptime by remember { mutableStateOf("") }
    LaunchedEffect(state.isRunning, start) {
        uptime = ""
        while (state.isRunning && start != null && isActive) {
            uptime = ServiceState.formatDuration(System.currentTimeMillis() - (start ?: break))
            delay(1000)
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(192.dp), contentAlignment = Alignment.Center) {
            if (state.isRunning || busy) {
                Box(Modifier.size(180.dp).clip(D2.Hero).background(container.copy(alpha = 0.08f)))
                Box(Modifier.size(160.dp).clip(D2.Hero).background(container.copy(alpha = 0.12f)))
            }
            Box(
                Modifier.size(140.dp).graphicsLayer {
                    shape = ExpressiveMorph.shape(ExpressiveMorph.SQUARE, ExpressiveMorph.COOKIE_12) { progress.value }
                    clip = true
                }.background(container)
                    .toggleable(state.isRunning, enabled = !state.isDisconnecting, role = Role.Switch, onValueChange = { onToggle() })
                    .semantics { contentDescription = action; stateDescription = status },
                contentAlignment = Alignment.Center
            ) {
                if (busy) LoadingIndicator(Modifier.size(64.dp), color = content)
                else Icon(if (state.isRunning) Icons.Default.Shield else Icons.Default.PowerSettingsNew, null, Modifier.size(56.dp), tint = content)
            }
        }
        Text(status, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        val mode = stringResource(if (state.isRootMode) R.string.dashboard_mode_root_tproxy else R.string.dashboard_mode_vpn)
        D2Caption(if (uptime.isEmpty()) mode else "$mode · $uptime")
    }
}

@Composable
private fun formatSpeed(value: Long): String = when {
    value >= 1048576 -> stringResource(R.string.speed_mb_per_s, value / 1048576.0)
    value >= 1024 -> stringResource(R.string.speed_kb_per_s, value / 1024.0)
    else -> stringResource(R.string.speed_b_per_s, value)
}
