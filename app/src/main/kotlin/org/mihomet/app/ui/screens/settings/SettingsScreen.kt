package org.mihomet.app.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import org.mihomet.app.R
import org.mihomet.app.data.preferences.ProxyMode
import org.mihomet.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToRouting: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onNavigateToConnections: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val settings = state.settings
    val context = LocalContext.current
    val view = LocalView.current

    var showPortsDialog by remember { mutableStateOf(false) }
    var showLatencyDialog by remember { mutableStateOf(false) }
    var showDnsDialog by remember { mutableStateOf(false) }
    var isAdvancedExpanded by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.nav_settings),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black
                    )
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 100.dp)
        ) {
            // 1. Connection Mode Grouped Card
            item {
                SettingsSectionTitle(stringResource(R.string.settings_connection_mode))
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ExpressiveShapes.Large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ExpressiveModeSelectRow(
                            icon = Icons.Default.VpnKey,
                            iconBg = MaterialTheme.colorScheme.primaryContainer,
                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                            title = stringResource(R.string.settings_mode_vpn_title),
                            description = stringResource(R.string.settings_mode_vpn_desc),
                            selected = !settings.isRootMode,
                            onSelect = {
                                AppHaptics.perform(context, HapticType.CLICK, view)
                                viewModel.updateRootMode(false)
                            }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                        ExpressiveModeSelectRow(
                            icon = Icons.Default.AdminPanelSettings,
                            iconBg = AccentOrangeContainer,
                            iconTint = AccentOrange,
                            title = stringResource(R.string.settings_mode_root_title),
                            description = stringResource(R.string.settings_mode_root_desc),
                            selected = settings.isRootMode,
                            onSelect = {
                                AppHaptics.perform(context, HapticType.CLICK, view)
                                viewModel.updateRootMode(true)
                            }
                        )
                    }
                }
            }

            // 2. Routing & App Rules Card
            item {
                SettingsSectionTitle(stringResource(R.string.settings_routing_section))
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ExpressiveShapes.Large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_proxy_mode),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = settings.proxyMode == ProxyMode.RULE,
                                onClick = {
                                    AppHaptics.perform(context, HapticType.CLICK, view)
                                    viewModel.updateProxyMode(ProxyMode.RULE)
                                },
                                shape = SegmentedButtonDefaults.itemShape(0, 3)
                            ) {
                                Text(stringResource(R.string.mode_rule), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            SegmentedButton(
                                selected = settings.proxyMode == ProxyMode.GLOBAL,
                                onClick = {
                                    AppHaptics.perform(context, HapticType.CLICK, view)
                                    viewModel.updateProxyMode(ProxyMode.GLOBAL)
                                },
                                shape = SegmentedButtonDefaults.itemShape(1, 3)
                            ) {
                                Text(stringResource(R.string.mode_global), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            SegmentedButton(
                                selected = settings.proxyMode == ProxyMode.DIRECT,
                                onClick = {
                                    AppHaptics.perform(context, HapticType.CLICK, view)
                                    viewModel.updateProxyMode(ProxyMode.DIRECT)
                                },
                                shape = SegmentedButtonDefaults.itemShape(2, 3)
                            ) {
                                Text(stringResource(R.string.mode_direct_short), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                        // App Exclusions Clickable Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ExpressiveShapes.Medium)
                                .clickable {
                                    AppHaptics.perform(context, HapticType.CLICK, view)
                                    onNavigateToRouting()
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = ExpressiveShapes.Small,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Apps,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Column {
                                    Text(
                                        text = stringResource(R.string.settings_app_exclusions),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_app_exclusions_desc),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 3. Notifications Card
            item {
                SettingsSectionTitle(stringResource(R.string.settings_notifications))
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ExpressiveShapes.Large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f).padding(end = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = ExpressiveShapes.Small,
                                    color = AccentGreenContainer,
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.NotificationsActive,
                                            contentDescription = null,
                                            tint = AccentGreenLight,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Column {
                                    Text(
                                        text = stringResource(R.string.settings_speed_notification),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_speed_notification_desc),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Switch(
                                checked = settings.showSpeedInNotification,
                                onCheckedChange = {
                                    AppHaptics.perform(context, HapticType.CLICK, view)
                                    viewModel.updateShowSpeedInNotification(it)
                                }
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp).padding(top = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.settings_notification_note),
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 4. Appearance Card
            item {
                SettingsSectionTitle(stringResource(R.string.settings_appearance))
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ExpressiveShapes.Large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ExpressiveSwitchRow(
                            icon = Icons.Default.Palette,
                            iconBg = MaterialTheme.colorScheme.primaryContainer,
                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                            title = stringResource(R.string.settings_dynamic_colors),
                            subtitle = stringResource(R.string.settings_dynamic_colors_desc),
                            checked = settings.dynamicColor,
                            onCheckedChange = {
                                AppHaptics.perform(context, HapticType.CLICK, view)
                                viewModel.updateTheme(it, settings.darkTheme, settings.amoledBlack)
                            }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                        ExpressiveSwitchRow(
                            icon = Icons.Default.DarkMode,
                            iconBg = SecondaryContainerDark,
                            iconTint = SecondaryDark,
                            title = stringResource(R.string.settings_dark_theme),
                            subtitle = stringResource(R.string.settings_dark_theme_desc),
                            checked = settings.darkTheme,
                            onCheckedChange = {
                                AppHaptics.perform(context, HapticType.CLICK, view)
                                viewModel.updateTheme(settings.dynamicColor, it, settings.amoledBlack)
                            }
                        )

                        if (settings.darkTheme) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                            ExpressiveSwitchRow(
                                icon = Icons.Default.Brightness2,
                                iconBg = DarkSurfaceContainerHighest,
                                iconTint = Color.White,
                                title = stringResource(R.string.settings_amoled),
                                subtitle = stringResource(R.string.settings_amoled_desc),
                                checked = settings.amoledBlack,
                                onCheckedChange = {
                                    AppHaptics.perform(context, HapticType.CLICK, view)
                                    viewModel.updateTheme(settings.dynamicColor, settings.darkTheme, it)
                                }
                            )
                        }
                    }
                }
            }

            // 5. Advanced Settings (Expandable)
            item {
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
                                    isAdvancedExpanded = !isAdvancedExpanded
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
                                            showPortsDialog = true
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
                                            showLatencyDialog = true
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
                                            showDnsDialog = true
                                        }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(stringResource(R.string.settings_dns), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        val dnsSubtitle = when {
                                            settings.customDns.isBlank() -> stringResource(R.string.settings_dns_default)
                                            settings.customDns == "https://dns.adguard-dns.com/dns-query" -> stringResource(R.string.settings_dns_adguard)
                                            settings.customDns == "https://cloudflare-dns.com/dns-query" -> stringResource(R.string.settings_dns_cloudflare)
                                            settings.customDns == "https://dns.google/dns-query" -> stringResource(R.string.settings_dns_google)
                                            settings.customDns == "https://common.dot.dns.yandex.net/dns-query" -> stringResource(R.string.settings_dns_yandex)
                                            settings.customDns == "https://dns.comss.one/dns-query" -> stringResource(R.string.settings_dns_comss)
                                            settings.customDns == "https://dns.quad9.net/dns-query" -> stringResource(R.string.settings_dns_quad9)
                                            else -> settings.customDns
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

            // 6. About App Card
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ExpressiveShapes.Large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = ExpressiveShapes.Small,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Shield,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            Column {
                                Text(stringResource(R.string.settings_about_title), fontWeight = FontWeight.Black, fontSize = 15.sp)
                                Text(stringResource(R.string.settings_about_edition), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = stringResource(R.string.settings_about_desc),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (showPortsDialog) {
            PortsConfigDialog(
                currentSettings = settings,
                onDismiss = { showPortsDialog = false },
                onSave = { mixed, tproxy, dns, controller ->
                    viewModel.updatePorts(mixed, tproxy, dns, controller)
                    showPortsDialog = false
                }
            )
        }

        if (showLatencyDialog) {
            LatencyTestConfigDialog(
                currentUrl = settings.testUrl,
                currentTimeoutMs = settings.testTimeoutMs,
                onDismiss = { showLatencyDialog = false },
                onSave = { url, timeout ->
                    viewModel.updateTestUrl(url)
                    viewModel.updateTestTimeout(timeout)
                    showLatencyDialog = false
                }
            )
        }

        if (showDnsDialog) {
            CustomDnsDialog(
                currentDns = settings.customDns,
                onDismiss = { showDnsDialog = false },
                onSave = { selectedDns ->
                    viewModel.updateCustomDns(selectedDns)
                    showDnsDialog = false
                }
            )
        }
    }
}

@Composable
fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Black,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
    )
}

@Composable
fun ExpressiveModeSelectRow(
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ExpressiveShapes.Medium)
            .clickable { onSelect() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = ExpressiveShapes.Small,
            color = iconBg,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Text(
                text = description,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        if (selected) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        } else {
            Surface(
                shape = CircleShape,
                color = Color.Transparent,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.size(20.dp)
            ) {}
        }
    }
}

@Composable
fun ExpressiveSwitchRow(
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = ExpressiveShapes.Small,
                color = iconBg,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun PortsConfigDialog(
    currentSettings: org.mihomet.app.data.preferences.AppSettings,
    onDismiss: () -> Unit,
    onSave: (Int, Int, Int, Int) -> Unit
) {
    var mixedPort by remember { mutableStateOf(currentSettings.mixedPort.toString()) }
    var tproxyPort by remember { mutableStateOf(currentSettings.tproxyPort.toString()) }
    var dnsPort by remember { mutableStateOf(currentSettings.dnsPort.toString()) }
    var controllerPort by remember { mutableStateOf(currentSettings.controllerPort.toString()) }

    val numberError = stringResource(R.string.settings_port_error_number)
    val rangeError = stringResource(R.string.settings_port_error_range)

    fun validate(value: String): String? {
        val port = value.toIntOrNull() ?: return numberError
        if (port !in 1024..65535) return rangeError
        return null
    }

    val mixedError = validate(mixedPort)
    val tproxyError = validate(tproxyPort)
    val dnsError = validate(dnsPort)
    val controllerError = validate(controllerPort)

    val parsedPorts = listOf(mixedPort, tproxyPort, dnsPort, controllerPort).mapNotNull { it.toIntOrNull() }
    val duplicateError =
        if (parsedPorts.size == 4 && parsedPorts.size != parsedPorts.distinct().size) {
            stringResource(R.string.settings_port_error_duplicate)
        } else {
            null
        }
    val isValid = mixedError == null && tproxyError == null && dnsError == null &&
            controllerError == null && duplicateError == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_ports_dialog_title), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = mixedPort,
                    onValueChange = { mixedPort = it },
                    label = { Text(stringResource(R.string.settings_port_mixed)) },
                    shape = ExpressiveShapes.Small,
                    singleLine = true,
                    isError = mixedError != null,
                    supportingText = { mixedError?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tproxyPort,
                    onValueChange = { tproxyPort = it },
                    label = { Text(stringResource(R.string.settings_port_tproxy)) },
                    shape = ExpressiveShapes.Small,
                    singleLine = true,
                    isError = tproxyError != null,
                    supportingText = { tproxyError?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dnsPort,
                    onValueChange = { dnsPort = it },
                    label = { Text(stringResource(R.string.settings_port_dns)) },
                    shape = ExpressiveShapes.Small,
                    singleLine = true,
                    isError = dnsError != null,
                    supportingText = { dnsError?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = controllerPort,
                    onValueChange = { controllerPort = it },
                    label = { Text(stringResource(R.string.settings_port_controller)) },
                    shape = ExpressiveShapes.Small,
                    singleLine = true,
                    isError = controllerError != null,
                    supportingText = { controllerError?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                if (duplicateError != null) {
                    Text(
                        text = duplicateError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(mixedPort.toInt(), tproxyPort.toInt(), dnsPort.toInt(), controllerPort.toInt())
                },
                enabled = isValid,
                shape = ExpressiveShapes.Small
            ) {
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

@Composable
fun LatencyTestConfigDialog(
    currentUrl: String,
    currentTimeoutMs: Int,
    onDismiss: () -> Unit,
    onSave: (String, Int) -> Unit
) {
    var url by remember { mutableStateOf(currentUrl) }
    var timeoutSec by remember { mutableStateOf((currentTimeoutMs / 1000).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_latency_dialog_title), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.settings_latency_url_label)) },
                    placeholder = { Text("http://www.gstatic.com/generate_204") },
                    shape = ExpressiveShapes.Small,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = timeoutSec,
                    onValueChange = { timeoutSec = it },
                    label = { Text(stringResource(R.string.settings_latency_timeout_label)) },
                    shape = ExpressiveShapes.Small,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { url = "http://www.gstatic.com/generate_204" },
                        modifier = Modifier.weight(1f),
                        shape = ExpressiveShapes.Small
                    ) {
                        Text("Google", fontSize = 11.sp)
                    }
                    FilledTonalButton(
                        onClick = { url = "http://cp.cloudflare.com/generate_204" },
                        modifier = Modifier.weight(1f),
                        shape = ExpressiveShapes.Small
                    ) {
                        Text("Cloudflare", fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val t = (timeoutSec.toIntOrNull() ?: 5) * 1000
                    onSave(url, t)
                },
                shape = ExpressiveShapes.Small
            ) {
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

@Composable
fun CustomDnsDialog(
    currentDns: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var dnsInput by remember { mutableStateOf(currentDns) }
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    val presets = listOf(
        stringResource(R.string.dns_preset_default) to "",
        stringResource(R.string.dns_preset_adguard) to "https://dns.adguard-dns.com/dns-query",
        stringResource(R.string.dns_preset_cloudflare) to "https://cloudflare-dns.com/dns-query",
        stringResource(R.string.dns_preset_google) to "https://dns.google/dns-query",
        stringResource(R.string.dns_preset_yandex) to "https://common.dot.dns.yandex.net/dns-query",
        stringResource(R.string.dns_preset_comss) to "https://dns.comss.one/dns-query",
        stringResource(R.string.dns_preset_quad9) to "https://dns.quad9.net/dns-query"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.settings_dns_dialog_title), fontWeight = FontWeight.Black)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_dns_dialog_desc),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = dnsInput,
                    onValueChange = { dnsInput = it },
                    label = { Text(stringResource(R.string.settings_dns_input_label)) },
                    placeholder = { Text("https://dns.adguard-dns.com/dns-query") },
                    shape = ExpressiveShapes.Small,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (dnsInput.isNotBlank()) {
                                IconButton(onClick = { dnsInput = "" }) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = stringResource(R.string.cd_clear),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            IconButton(onClick = {
                                clipboardManager.getText()?.text?.let { text ->
                                    dnsInput = text.trim()
                                }
                            }) {
                                Icon(
                                    Icons.Default.ContentPaste,
                                    contentDescription = stringResource(R.string.cd_paste),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                )

                Text(
                    text = stringResource(R.string.settings_dns_presets_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )

                presets.forEach { (name, url) ->
                    val isSelected = (dnsInput.trim() == url)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ExpressiveShapes.Small)
                            .clickable { dnsInput = url },
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
                        },
                        shape = ExpressiveShapes.Small,
                        border = if (isSelected) {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                        } else null
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = name,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                if (url.isNotBlank()) {
                                    Text(
                                        text = url,
                                        fontSize = 11.sp,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.settings_dns_default_desc),
                                        fontSize = 11.sp,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }

                            if (isSelected) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(dnsInput.trim()) },
                shape = ExpressiveShapes.Small
            ) {
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
