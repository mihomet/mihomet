package org.mihomet.app.ui.screens.settings

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.Article
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
import java.util.Locale
import org.koin.androidx.compose.koinViewModel
import org.mihomet.app.R
import org.mihomet.app.core.HappCrypto
import org.mihomet.app.core.OemHints
import org.mihomet.app.core.dns.DnsCategory
import org.mihomet.app.core.dns.DnsPresets
import org.mihomet.app.data.preferences.ProfileOverrides
import org.mihomet.app.data.preferences.ProxyMode
import org.mihomet.app.data.preferences.TunOptions
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

    // Manufacturer diagnostics — only on devices from vendors whose power
    // managers are known to kill background VPN services (MIUI, ColorOS, …).
    val oemBrand = remember { OemHints.detect() }
    val oemLaunch = remember(oemBrand) { OemHints.resolveLaunchableOrGeneric(context, oemBrand) }
    val oemLabel = remember { Build.MANUFACTURER.replaceFirstChar { it.uppercase(Locale.ROOT) } }

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

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                        // Autostart on boot (BootReceiver + last successfully started mode)
                        ExpressiveSwitchRow(
                            icon = Icons.Default.RestartAlt,
                            iconBg = MaterialTheme.colorScheme.tertiaryContainer,
                            iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                            title = stringResource(R.string.settings_autostart),
                            subtitle = stringResource(R.string.settings_autostart_desc),
                            checked = settings.autoStartOnBoot,
                            onCheckedChange = {
                                AppHaptics.perform(context, HapticType.CLICK, view)
                                viewModel.updateAutoStartOnBoot(it)
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

                                // TUN MTU (tap to cycle 1500 → 8500 → 9000)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(ExpressiveShapes.Small)
                                        .clickable {
                                            AppHaptics.perform(context, HapticType.CLICK, view)
                                            val choices = TunOptions.MTU_CHOICES
                                            val currentIndex = choices.indexOf(settings.tunMtu)
                                            val next = choices[(currentIndex + 1).mod(choices.size)]
                                            viewModel.updateTunMtu(next)
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

                                // Happ crypto keyring (encrypted subscriptions)
                                HappCryptoKeysSection(
                                    keys = state.cryptoKeys,
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

            // 6. Manufacturer diagnostics card (known OEM ROMs only)
            if (oemBrand != OemHints.OemBrand.STOCK) {
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
                                    color = AccentOrangeContainer,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.BatteryAlert,
                                            contentDescription = null,
                                            tint = AccentOrange,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                Column {
                                    Text(
                                        stringResource(R.string.oem_diag_title),
                                        fontWeight = FontWeight.Black,
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        stringResource(R.string.oem_diag_desc, oemLabel),
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (oemLaunch != null) {
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedButton(
                                    onClick = {
                                        AppHaptics.perform(context, HapticType.CLICK, view)
                                        runCatching { context.startActivity(oemLaunch) }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = ExpressiveShapes.Medium
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.oem_diag_open), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }

            // 7. About App Card
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

                // Default choice: built-in smart resolver pool, no category badge.
                DnsPresetRow(
                    name = stringResource(R.string.dns_preset_default),
                    url = "",
                    isSelected = dnsInput.isBlank(),
                    onSelect = { dnsInput = "" }
                )

                // Presets grouped by reachability category, each group with a colored badge.
                DnsCategory.entries.forEach { category ->
                    val categoryPresets = DnsPresets.byCategory(category)
                    if (categoryPresets.isNotEmpty()) {
                        DnsCategoryBadge(category)
                        categoryPresets.forEach { preset ->
                            DnsPresetRow(
                                name = DnsPresetDisplayName(preset.uri),
                                url = preset.uri,
                                isSelected = dnsInput.trim() == preset.uri,
                                onSelect = { dnsInput = preset.uri }
                            )
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

/**
 * Happ crypto keyring editor (Settings → Advanced): lists the stored keys as
 * id + masked value rows with a delete action, and provides id/key inputs
 * guarded by the 16-byte AES-128 length check (see core/HappCrypto.kt).
 */
@Composable
private fun HappCryptoKeysSection(
    keys: Map<String, String>,
    onAdd: (String, String) -> Unit,
    onRemove: (String) -> Unit
) {
    var keyId by remember { mutableStateOf("") }
    var keyValue by remember { mutableStateOf("") }

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
            onClick = {
                onAdd(keyId.trim(), keyValue.trim())
                keyId = ""
                keyValue = ""
            },
            enabled = canAdd,
            modifier = Modifier.fillMaxWidth(),
            shape = ExpressiveShapes.Small
        ) {
            Text(stringResource(R.string.settings_happ_key_add), fontWeight = FontWeight.Bold)
        }
    }
}

/** One selectable DNS preset row inside [CustomDnsDialog]. */
@Composable
private fun DnsPresetRow(
    name: String,
    url: String,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ExpressiveShapes.Small)
            .clickable { onSelect() },
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

/** Colored reachability badge shown above each preset group in [CustomDnsDialog]. */
@Composable
private fun DnsCategoryBadge(category: DnsCategory) {
    val color = when (category) {
        // Brand accents: teal = plain, violet = encrypted RU, amber = caution (global).
        DnsCategory.PLAINTEXT_RU -> Color(0xFF2DD4BF)
        DnsCategory.ENCRYPTED_RU -> PrimaryViolet
        DnsCategory.GLOBAL -> AccentOrange
    }
    Surface(
        shape = ExpressiveShapes.Pill,
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = stringResource(category.labelRes),
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/** Human-readable preset name for a saved DNS URI; falls back to the raw URI for custom servers. */
@Composable
fun DnsPresetDisplayName(uri: String): String {
    val preset = DnsPresets.ALL.firstOrNull { it.uri == uri.trim() } ?: return uri
    return when (preset.id) {
        "yandex_plain_1", "yandex_plain_2" -> stringResource(R.string.dns_preset_yandex_plain)
        "yandex_doh" -> stringResource(R.string.dns_preset_yandex)
        "comss_doh" -> stringResource(R.string.dns_preset_comss)
        "adguard_doh" -> stringResource(R.string.dns_preset_adguard)
        "cloudflare_doh_ip" -> stringResource(R.string.dns_preset_cloudflare_ip)
        "cloudflare_doh" -> stringResource(R.string.dns_preset_cloudflare)
        "google_doh" -> stringResource(R.string.dns_preset_google)
        "quad9_doh" -> stringResource(R.string.dns_preset_quad9)
        else -> preset.uri
    }
}
