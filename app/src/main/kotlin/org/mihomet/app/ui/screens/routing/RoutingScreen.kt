package org.mihomet.app.ui.screens.routing

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import org.koin.androidx.compose.koinViewModel
import org.mihomet.app.data.preferences.FirewallEngineType
import org.mihomet.app.data.preferences.PerAppMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingScreen(
    viewModel: RoutingViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val settings = state.settings

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Routing & Firewall", fontWeight = FontWeight.Bold) }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // Execution Mode Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Execution Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Root TProxy Mode", fontWeight = FontWeight.Medium)
                                Text(
                                    "Bypasses Android VpnService via netfilter (nftables/iptables). Requires Superuser.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = settings.isRootMode,
                                onCheckedChange = { viewModel.updateRootMode(it) }
                            )
                        }
                    }
                }
            }

            // Firewall Engine Card (Only shown if root is enabled)
            if (settings.isRootMode) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Firewall Engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(12.dp))

                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                SegmentedButton(
                                    selected = settings.firewallEngine == FirewallEngineType.AUTO,
                                    onClick = { viewModel.updateFirewallEngine(FirewallEngineType.AUTO) },
                                    shape = SegmentedButtonDefaults.itemShape(0, 3)
                                ) {
                                    Text("Auto")
                                }
                                SegmentedButton(
                                    selected = settings.firewallEngine == FirewallEngineType.NFTABLES,
                                    onClick = { viewModel.updateFirewallEngine(FirewallEngineType.NFTABLES) },
                                    shape = SegmentedButtonDefaults.itemShape(1, 3)
                                ) {
                                    Text("nftables")
                                }
                                SegmentedButton(
                                    selected = settings.firewallEngine == FirewallEngineType.IPTABLES,
                                    onClick = { viewModel.updateFirewallEngine(FirewallEngineType.IPTABLES) },
                                    shape = SegmentedButtonDefaults.itemShape(2, 3)
                                ) {
                                    Text("iptables")
                                }
                            }
                        }
                    }
                }
            }

            // IPv6 Card
            item {
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable IPv6 Routing", fontWeight = FontWeight.Medium)
                            Text(
                                "When disabled, IPv6 is blocked to prevent data leaks.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.ipv6Enabled,
                            onCheckedChange = { viewModel.updateIpv6Enabled(it) }
                        )
                    }
                }
            }

            // Per-App Proxy Configuration Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Per-App Proxy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(12.dp))

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = settings.perAppMode == PerAppMode.ALL,
                                onClick = { viewModel.updatePerAppMode(PerAppMode.ALL) },
                                shape = SegmentedButtonDefaults.itemShape(0, 3)
                            ) {
                                Text("All Apps")
                            }
                            SegmentedButton(
                                selected = settings.perAppMode == PerAppMode.WHITELIST,
                                onClick = { viewModel.updatePerAppMode(PerAppMode.WHITELIST) },
                                shape = SegmentedButtonDefaults.itemShape(1, 3)
                            ) {
                                Text("Whitelist")
                            }
                            SegmentedButton(
                                selected = settings.perAppMode == PerAppMode.BLACKLIST,
                                onClick = { viewModel.updatePerAppMode(PerAppMode.BLACKLIST) },
                                shape = SegmentedButtonDefaults.itemShape(2, 3)
                            ) {
                                Text("Blacklist")
                            }
                        }

                        if (settings.perAppMode != PerAppMode.ALL) {
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedTextField(
                                value = state.appSearchQuery,
                                onValueChange = { viewModel.updateSearchQuery(it) },
                                placeholder = { Text("Search installed applications...") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                singleLine = true
                            )
                        }
                    }
                }
            }

            // Application list (when Whitelist or Blacklist is selected)
            if (settings.perAppMode != PerAppMode.ALL) {
                val filteredApps = state.installedApps.filter {
                    state.appSearchQuery.isBlank() ||
                            it.label.contains(state.appSearchQuery, ignoreCase = true) ||
                            it.packageName.contains(state.appSearchQuery, ignoreCase = true)
                }

                if (state.isLoadingApps) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val isChecked = settings.selectedAppUids.contains(app.uid)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.toggleAppUid(app.uid) },
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (app.icon != null) {
                                        Image(
                                            bitmap = app.icon.toBitmap().asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = app.label,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${app.packageName} (UID: ${app.uid})",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { viewModel.toggleAppUid(app.uid) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
