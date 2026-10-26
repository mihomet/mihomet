package org.mihomet.app.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
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
    var isAdvancedExpanded by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Настройки",
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
                SettingsSectionTitle("Режим подключения")
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
                            title = "Без Root (VPN)",
                            description = "Стандартный безопасный режим VPN для любых устройств Android. Не требует root-прав.",
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
                            title = "Root (TProxy)",
                            description = "Прозрачное проксирование сетевого стека через netfilter (nftables/iptables). Требует KernelSU или Magisk.",
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
                SettingsSectionTitle("Маршрутизация и правила")
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
                            text = "Режим проксирования",
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
                                Text("Умный", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            SegmentedButton(
                                selected = settings.proxyMode == ProxyMode.GLOBAL,
                                onClick = {
                                    AppHaptics.perform(context, HapticType.CLICK, view)
                                    viewModel.updateProxyMode(ProxyMode.GLOBAL)
                                },
                                shape = SegmentedButtonDefaults.itemShape(1, 3)
                            ) {
                                Text("Все сайты", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            SegmentedButton(
                                selected = settings.proxyMode == ProxyMode.DIRECT,
                                onClick = {
                                    AppHaptics.perform(context, HapticType.CLICK, view)
                                    viewModel.updateProxyMode(ProxyMode.DIRECT)
                                },
                                shape = SegmentedButtonDefaults.itemShape(2, 3)
                            ) {
                                Text("Прямой", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                                        text = "Исключения приложений",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        text = "Выбрать приложения в обход прокси (банки, Госуслуги)",
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
                SettingsSectionTitle("Уведомления")
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
                                        text = "Скорость в уведомлении",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Отображать текущую скорость передачи данных",
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
                                text = "Android требует постоянное фоновое уведомление для непрерывной работы VPN без выгрузки системой из памяти.",
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
                SettingsSectionTitle("Оформление")
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
                            title = "Динамические цвета Material You",
                            subtitle = "Цвета адаптируются под обои рабочего стола",
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
                            title = "Темная тема",
                            subtitle = "Комфортный режим для темного времени суток",
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
                                title = "Глубокий черный (AMOLED)",
                                subtitle = "Идеальный черный цвет и экономия батареи",
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
                                Text("Продвинутые настройки", fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
                                        Text("Сетевые порты", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text(
                                            "Mixed: ${settings.mixedPort} • TProxy: ${settings.tproxyPort} • DNS: ${settings.dnsPort}",
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
                                        Text("Проверка задержки узлов (Ping)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        val shortUrl = when (settings.testUrl) {
                                            "http://www.gstatic.com/generate_204" -> "Google (204)"
                                            "http://cp.cloudflare.com/generate_204", "https://cp.cloudflare.com/generate_204" -> "Cloudflare (204)"
                                            else -> settings.testUrl
                                        }
                                        Text(
                                            "URL: $shortUrl • Таймаут: ${settings.testTimeoutMs / 1000} сек.",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                                // IPv6
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Поддержка IPv6", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
                                        Text("Ядро Mihomo", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text("Версия: ${state.coreVersion}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                            Text("Проверить", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                                            Text("Обновить ядро до ${state.latestRelease?.tag_name}", fontWeight = FontWeight.Bold)
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
                                            Text("Переустановить ядро (${state.latestRelease?.tag_name})", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                if (state.updateMessage != null) {
                                    Text(
                                        text = state.updateMessage ?: "",
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
                                    Text("Открыть системный журнал (Logs)", fontWeight = FontWeight.SemiBold)
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
                                Text("Mihomet v1.0", fontWeight = FontWeight.Black, fontSize = 15.sp)
                                Text("Material 3 Expressive Edition", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Высокоскоростной прокси-клиент на базе ядра Mihomo с поддержкой Root TProxy, Non-Root VPN Service, VLESS Reality и Hysteria 2.",
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройка сетевых портов", fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = mixedPort,
                    onValueChange = { mixedPort = it },
                    label = { Text("Mixed Port (HTTP + SOCKS5)") },
                    shape = ExpressiveShapes.Small,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tproxyPort,
                    onValueChange = { tproxyPort = it },
                    label = { Text("TProxy Port (Root Mode)") },
                    shape = ExpressiveShapes.Small,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dnsPort,
                    onValueChange = { dnsPort = it },
                    label = { Text("DNS Port") },
                    shape = ExpressiveShapes.Small,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = controllerPort,
                    onValueChange = { controllerPort = it },
                    label = { Text("Mihomo API Controller Port") },
                    shape = ExpressiveShapes.Small,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val m = mixedPort.toIntOrNull() ?: currentSettings.mixedPort
                    val t = tproxyPort.toIntOrNull() ?: currentSettings.tproxyPort
                    val d = dnsPort.toIntOrNull() ?: currentSettings.dnsPort
                    val c = controllerPort.toIntOrNull() ?: currentSettings.controllerPort
                    onSave(m, t, d, c)
                },
                shape = ExpressiveShapes.Small
            ) {
                Text("Сохранить", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
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
        title = { Text("Проверка задержки (Ping)", fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL проверки (204 No Content)") },
                    placeholder = { Text("http://www.gstatic.com/generate_204") },
                    shape = ExpressiveShapes.Small,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = timeoutSec,
                    onValueChange = { timeoutSec = it },
                    label = { Text("Таймаут (секунды)") },
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
                Text("Сохранить", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
