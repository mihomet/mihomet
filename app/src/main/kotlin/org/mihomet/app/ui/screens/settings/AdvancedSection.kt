package org.mihomet.app.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mihomet.app.R
import org.mihomet.app.core.HappCrypto
import org.mihomet.app.data.preferences.ProfileOverrides
import org.mihomet.app.data.preferences.TunOptions
import org.mihomet.app.ui.theme.AccentGreenContainer
import org.mihomet.app.ui.theme.AccentGreenLight
import org.mihomet.app.ui.theme.AccentOrange
import org.mihomet.app.ui.theme.AccentOrangeContainer
import org.mihomet.app.ui.theme.AppHaptics
import org.mihomet.app.ui.theme.ExpressiveShapes
import org.mihomet.app.ui.theme.HapticType
import org.mihomet.app.ui.theme.OnSecondaryContainerDark
import org.mihomet.app.ui.theme.SecondaryContainerDark

/**
 * "Advanced" expandable card: ports, latency test, custom DNS, IPv6, TUN
 * stack/MTU, bypass-LAN, VPN allowBypass / HTTP proxy, profile overrides,
 * the Happ crypto keyring and the core update / logs entry points.
 */
@Composable
internal fun AdvancedSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onShowPortsDialog: () -> Unit,
    onShowLatencyDialog: () -> Unit,
    onShowDnsDialog: () -> Unit,
    onShowMtuDialog: () -> Unit,
    onNavigateToLogs: () -> Unit
) {
    val settings = state.settings
    val context = LocalContext.current
    val view = LocalView.current
    val isAdvancedExpanded = expanded

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ExpressiveShapes.Large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ExpressiveShapes.Medium)
                    .clickable {
                        AppHaptics.perform(context, HapticType.CLICK, view)
                        onExpandedChange(!isAdvancedExpanded)
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = ExpressiveShapes.Small,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(stringResource(R.string.settings_advanced), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                Icon(
                    if (isAdvancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = isAdvancedExpanded) {
                Column(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Port Configuration
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ExpressiveShapes.Small)
                            .clickable {
                                AppHaptics.perform(context, HapticType.CLICK, view)
                                onShowPortsDialog()
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(stringResource(R.string.settings_ports), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(
                                stringResource(
                                    R.string.settings_ports_summary,
                                    settings.mixedPort, settings.tproxyPort, settings.dnsPort
                                ),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // Latency Test Configuration
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ExpressiveShapes.Small)
                            .clickable {
                                AppHaptics.perform(context, HapticType.CLICK, view)
                                onShowLatencyDialog()
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(stringResource(R.string.settings_latency), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            val shortUrl = when (settings.testUrl) {
                                "http://www.gstatic.com/generate_204" -> "Google (204)"
                                "http://cp.cloudflare.com/generate_204", "https://cp.cloudflare.com/generate_204" -> "Cloudflare (204)"
                                else -> settings.testUrl
                            }
                            Text(
                                stringResource(R.string.settings_latency_summary, shortUrl, settings.testTimeoutMs / 1000),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // Custom DNS Configuration
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ExpressiveShapes.Small)
                            .clickable {
                                AppHaptics.perform(context, HapticType.CLICK, view)
                                onShowDnsDialog()
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(stringResource(R.string.settings_dns), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            val dnsSubtitle = if (settings.customDns.isBlank()) {
                                stringResource(R.string.settings_dns_default)
                            } else {
                                DnsPresetDisplayName(settings.customDns)
                            }
                            Text(
                                dnsSubtitle,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(18.dp))
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // IPv6
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.settings_ipv6), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Switch(
                            checked = settings.ipv6Enabled,
                            onCheckedChange = {
                                AppHaptics.perform(context, HapticType.CLICK, view)
                                viewModel.updateIpv6Enabled(it)
                            }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // TUN stack (tap to cycle gvisor → system → mixed, like the log-level row)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ExpressiveShapes.Small)
                            .clickable {
                                AppHaptics.perform(context, HapticType.CLICK, view)
                                val stacks = TunOptions.STACKS
                                val currentIndex = stacks.indexOf(settings.tunStack)
                                val next = stacks[(currentIndex + 1).mod(stacks.size)]
                                viewModel.updateTunStack(next)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                stringResource(R.string.settings_tun_stack),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                stringResource(R.string.settings_tun_stack_desc, settings.tunStack),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(18.dp))
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // TUN MTU — free numeric input (1280..65535), presets inside the dialog
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ExpressiveShapes.Small)
                            .clickable {
                                AppHaptics.perform(context, HapticType.CLICK, view)
                                onShowMtuDialog()
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                stringResource(R.string.settings_tun_mtu),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                stringResource(R.string.settings_tun_mtu_desc, settings.tunMtu),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Default.SettingsEthernet, contentDescription = null, modifier = Modifier.size(18.dp))
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // Bypass LAN (CMFA bypassPrivateNetwork pattern)
                    ExpressiveSwitchRow(
                        icon = Icons.Default.Router,
                        iconBg = MaterialTheme.colorScheme.secondaryContainer,
                        iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                        title = stringResource(R.string.settings_bypass_lan),
                        subtitle = stringResource(R.string.settings_bypass_lan_desc),
                        checked = settings.bypassLan,
                        onCheckedChange = {
                            AppHaptics.perform(context, HapticType.CLICK, view)
                            viewModel.updateBypassLan(it)
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // VpnService allowBypass (CMFA/FlClash pattern, opt-in)
                    ExpressiveSwitchRow(
                        icon = Icons.Default.AltRoute,
                        iconBg = MaterialTheme.colorScheme.secondaryContainer,
                        iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                        title = stringResource(R.string.vpn_bypass_title),
                        subtitle = stringResource(R.string.vpn_bypass_desc),
                        checked = settings.vpnAllowBypass,
                        onCheckedChange = {
                            AppHaptics.perform(context, HapticType.CLICK, view)
                            viewModel.updateVpnAllowBypass(it)
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // HTTP proxy via VPN (VpnService.setHttpProxy, opt-in)
                    ExpressiveSwitchRow(
                        icon = Icons.Default.Http,
                        iconBg = AccentGreenContainer,
                        iconTint = AccentGreenLight,
                        title = stringResource(R.string.vpn_httpproxy_title),
                        subtitle = stringResource(R.string.vpn_httpproxy_desc),
                        checked = settings.vpnHttpProxy,
                        onCheckedChange = {
                            AppHaptics.perform(context, HapticType.CLICK, view)
                            viewModel.updateVpnHttpProxy(it)
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // Profile override: unified-delay (RTT smoothing)
                    ExpressiveSwitchRow(
                        icon = Icons.Default.HourglassTop,
                        iconBg = AccentGreenContainer,
                        iconTint = AccentGreenLight,
                        title = stringResource(R.string.settings_override_unified_delay),
                        subtitle = stringResource(R.string.settings_override_unified_delay_desc),
                        checked = settings.overrideUnifiedDelay,
                        onCheckedChange = {
                            AppHaptics.perform(context, HapticType.CLICK, view)
                            viewModel.updateOverrideUnifiedDelay(it)
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // Profile override: tcp-concurrent (fast concurrent dial)
                    ExpressiveSwitchRow(
                        icon = Icons.Default.Bolt,
                        iconBg = AccentOrangeContainer,
                        iconTint = AccentOrange,
                        title = stringResource(R.string.settings_override_tcp_concurrent),
                        subtitle = stringResource(R.string.settings_override_tcp_concurrent_desc),
                        checked = settings.overrideTcpConcurrent,
                        onCheckedChange = {
                            AppHaptics.perform(context, HapticType.CLICK, view)
                            viewModel.updateOverrideTcpConcurrent(it)
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // Built-in auto-balancer: AUTO url-test group for group-less profiles
                    var showAutoBalancerExcludesDialog by remember { mutableStateOf(false) }
                    ExpressiveSwitchRow(
                        icon = Icons.Default.AltRoute,
                        iconBg = AccentGreenContainer,
                        iconTint = AccentGreenLight,
                        title = stringResource(R.string.settings_auto_balancer),
                        subtitle = stringResource(R.string.settings_auto_balancer_desc),
                        checked = settings.autoBalancerEnabled,
                        onCheckedChange = {
                            AppHaptics.perform(context, HapticType.CLICK, view)
                            viewModel.updateAutoBalancerEnabled(it)
                        }
                    )

                    if (settings.autoBalancerEnabled) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ExpressiveShapes.Medium)
                                .clickable {
                                    AppHaptics.perform(context, HapticType.CLICK, view)
                                    showAutoBalancerExcludesDialog = true
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = ExpressiveShapes.Small,
                                color = AccentOrangeContainer,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = null,
                                        tint = AccentOrange
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(
                                    text = stringResource(R.string.settings_auto_balancer_excludes),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = settings.autoBalancerExcludes.lineSequence()
                                        .filter { it.isNotBlank() }
                                        .joinToString(", ").ifBlank {
                                            stringResource(R.string.settings_auto_balancer_excludes_empty)
                                        },
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                        ExpressiveSwitchRow(
                            icon = Icons.Default.Bolt,
                            iconBg = AccentOrangeContainer,
                            iconTint = AccentOrange,
                            title = stringResource(R.string.settings_auto_balancer_prefer),
                            subtitle = stringResource(R.string.settings_auto_balancer_prefer_desc),
                            checked = settings.autoBalancerPreferDefault,
                            onCheckedChange = {
                                AppHaptics.perform(context, HapticType.CLICK, view)
                                viewModel.updateAutoBalancerPreferDefault(it)
                            }
                        )
                    }

                    if (showAutoBalancerExcludesDialog) {
                        AutoBalancerExcludesDialog(
                            current = settings.autoBalancerExcludes,
                            onDismiss = { showAutoBalancerExcludesDialog = false },
                            onSave = { patterns ->
                                viewModel.updateAutoBalancerExcludes(patterns)
                                showAutoBalancerExcludesDialog = false
                            }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // Profile override: domain sniffer
                    ExpressiveSwitchRow(
                        icon = Icons.Default.Search,
                        iconBg = SecondaryContainerDark,
                        iconTint = OnSecondaryContainerDark,
                        title = stringResource(R.string.settings_override_sniffer),
                        subtitle = stringResource(R.string.settings_override_sniffer_desc),
                        checked = settings.overrideSnifferEnabled,
                        onCheckedChange = {
                            AppHaptics.perform(context, HapticType.CLICK, view)
                            viewModel.updateOverrideSnifferEnabled(it)
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // Profile override: log level (tap to cycle info → warning → error → silent)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ExpressiveShapes.Small)
                            .clickable {
                                AppHaptics.perform(context, HapticType.CLICK, view)
                                val levels = ProfileOverrides.LOG_LEVELS
                                val currentIndex = levels.indexOf(settings.overrideLogLevel)
                                val next = levels[(currentIndex + 1).mod(levels.size)]
                                viewModel.updateOverrideLogLevel(next)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                stringResource(R.string.settings_override_log_level),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                stringResource(R.string.settings_override_log_level_desc, settings.overrideLogLevel),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null, modifier = Modifier.size(18.dp))
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // Persistent continuous background logging
                    ExpressiveSwitchRow(
                        icon = Icons.Default.Terminal,
                        iconBg = SecondaryContainerDark,
                        iconTint = OnSecondaryContainerDark,
                        title = stringResource(R.string.settings_persistent_logging_title),
                        subtitle = stringResource(R.string.settings_persistent_logging_desc),
                        checked = settings.persistentLogging,
                        onCheckedChange = {
                            AppHaptics.perform(context, HapticType.CLICK, view)
                            viewModel.updatePersistentLogging(it)
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // Happ crypto keyring (encrypted subscriptions)
                    HappCryptoKeysSection(
                        keys = state.cryptoKeys,
                        keyringEvent = state.keyringEvent,
                        onAdd = viewModel::addCryptoKey,
                        onRemove = viewModel::removeCryptoKey
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // Mihomo Core Section
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(stringResource(R.string.settings_core), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(stringResource(R.string.settings_core_version, state.coreVersion), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FilledTonalButton(
                            onClick = {
                                AppHaptics.perform(context, HapticType.CLICK, view)
                                viewModel.checkForCoreUpdate()
                            },
                            enabled = !state.isCheckingUpdate && !state.isDownloadingUpdate,
                            shape = ExpressiveShapes.Small
                        ) {
                            if (state.isCheckingUpdate) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.settings_check), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // F15: scope disclosure — the in-app update replaces the
                    // ROOT daemon binary (/data/adb/mihomet/bin/mihomo) only;
                    // the VPN core is bundled into the APK and ships with
                    // app updates.
                    Text(
                        text = stringResource(R.string.subs_update_scope),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (state.latestRelease != null && state.latestRelease?.tag_name != state.coreVersion) {
                        Button(
                            onClick = {
                                AppHaptics.perform(context, HapticType.CLICK, view)
                                viewModel.installCoreUpdate()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isDownloadingUpdate,
                            shape = ExpressiveShapes.Medium
                        ) {
                            if (state.isDownloadingUpdate) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                Text(stringResource(R.string.settings_update_core_to, state.latestRelease?.tag_name ?: ""), fontWeight = FontWeight.Bold)
                            }
                        }
                    } else if (state.latestRelease != null) {
                        OutlinedButton(
                            onClick = {
                                AppHaptics.perform(context, HapticType.CLICK, view)
                                viewModel.installCoreUpdate()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isDownloadingUpdate,
                            shape = ExpressiveShapes.Medium
                        ) {
                            if (state.isDownloadingUpdate) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.settings_reinstall_core, state.latestRelease?.tag_name ?: ""), fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    val updateMessageRes = state.updateMessageRes
                    if (updateMessageRes != null) {
                        Text(
                            text = stringResource(updateMessageRes, *state.updateMessageArgs.toTypedArray()),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // Logs button
                    OutlinedButton(
                        onClick = onNavigateToLogs,
                        modifier = Modifier.fillMaxWidth(),
                        shape = ExpressiveShapes.Medium
                    ) {
                        Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_open_logs), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/**
 * Happ crypto keyring editor (Settings → Advanced): lists the stored keys as
 * id + masked value rows with a delete action, and provides id/key inputs
 * guarded by the 16-byte AES-128 length check (see core/HappCrypto.kt).
 *
 * [keyringEvent] is the ViewModel's last add-attempt outcome: the inputs are
 * cleared only after a confirmed successful save — a failed attempt (the
 * crypto gate refused to encrypt, so nothing was persisted) keeps what the
 * user typed so it can be retried without re-entering the key. The snackbar
 * for both outcomes is handled by the screen-level host.
 */
@Composable
private fun HappCryptoKeysSection(
    keys: Map<String, String>,
    keyringEvent: KeyringEvent?,
    onAdd: (String, String) -> Unit,
    onRemove: (String) -> Unit
) {
    var keyId by remember { mutableStateOf("") }
    var keyValue by remember { mutableStateOf("") }

    // Clear the inputs only on a confirmed success (unique event id → every
    // success re-triggers; failures leave the fields untouched).
    LaunchedEffect(keyringEvent?.id) {
        if (keyringEvent?.success == true) {
            keyId = ""
            keyValue = ""
        }
    }

    val isKeyLengthValid = keyValue.toByteArray(Charsets.UTF_8).size == HappCrypto.KEY_BYTES
    val showLengthError = keyValue.isNotEmpty() && !isKeyLengthValid
    val canAdd = keyId.isNotBlank() && isKeyLengthValid

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = ExpressiveShapes.Small,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Text(
                stringResource(R.string.settings_happ_keys),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }

        keys.entries.sortedBy { it.key }.forEach { entry ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = ExpressiveShapes.Small,
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            entry.key,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "••••",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onRemove(entry.key) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = keyId,
            onValueChange = { keyId = it },
            label = { Text(stringResource(R.string.settings_happ_key_id)) },
            shape = ExpressiveShapes.Small,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = keyValue,
            onValueChange = { keyValue = it },
            label = { Text(stringResource(R.string.settings_happ_key_value)) },
            shape = ExpressiveShapes.Small,
            singleLine = true,
            isError = showLengthError,
            supportingText = {
                if (showLengthError) {
                    Text(stringResource(R.string.settings_happ_key_error_length))
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { onAdd(keyId.trim(), keyValue.trim()) },
            enabled = canAdd,
            modifier = Modifier.fillMaxWidth(),
            shape = ExpressiveShapes.Small
        ) {
            Text(stringResource(R.string.settings_happ_key_add), fontWeight = FontWeight.Bold)
        }
    }
}


/** Multiline editor for auto-balancer node-name exclusions (one substring per line). */
@Composable
private fun AutoBalancerExcludesDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var input by remember { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_auto_balancer_excludes), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text(stringResource(R.string.settings_auto_balancer_excludes_hint)) },
                    shape = ExpressiveShapes.Small,
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.settings_auto_balancer_excludes_hint),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(input) }, shape = ExpressiveShapes.Small) {
                Text(stringResource(R.string.action_save), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
