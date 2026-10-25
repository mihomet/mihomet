package org.mihomet.app.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import org.mihomet.app.data.preferences.FirewallEngineType
import org.mihomet.app.data.preferences.ProxyMode

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

    var showPortsDialog by remember { mutableStateOf(false) }
    var isAdvancedExpanded by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Настройки", fontWeight = FontWeight.Bold) }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
        ) {
            // 1. Connection Mode Card
            item {
                SettingsSectionTitle("Режим подключения")
                Spacer(modifier = Modifier.height(6.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ModeSelectRow(
                            title = "Без Root (VPN)",
                            description = "Стандартный безопасный режим VPN для любых устройств Android. Не требует root-прав.",
                            selected = !settings.isRootMode,
                            onSelect = { viewModel.updateRootMode(false) }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        ModeSelectRow(
                            title = "Root (TProxy)",
                            description = "Прозрачное проксирование сетевого стека через netfilter (nftables/iptables). Требует Magisk/KernelSU.",
                            selected = settings.isRootMode,
                            onSelect = { viewModel.updateRootMode(true) }
                        )
                    }
                }
            }

            // 2. Routing & App Rules Card
            item {
                SettingsSectionTitle("Маршрутизация и приложения")
                Spacer(modifier = Modifier.height(6.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            text = "Режим проксирования",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = settings.proxyMode == ProxyMode.RULE,
                                onClick = { viewModel.updateProxyMode(ProxyMode.RULE) },
                                shape = SegmentedButtonDefaults.itemShape(0, 3)
                            ) {
                                Text("Умный", fontSize = 12.sp)
                            }
                            SegmentedButton(
                                selected = settings.proxyMode == ProxyMode.GLOBAL,
                                onClick = { viewModel.updateProxyMode(ProxyMode.GLOBAL) },
                                shape = SegmentedButtonDefaults.itemShape(1, 3)
                            ) {
                                Text("Все сайты", fontSize = 12.sp)
                            }
                            SegmentedButton(
                                selected = settings.proxyMode == ProxyMode.DIRECT,
                                onClick = { viewModel.updateProxyMode(ProxyMode.DIRECT) },
                                shape = SegmentedButtonDefaults.itemShape(2, 3)
                            ) {
                                Text("Прямой", fontSize = 12.sp)
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // App Exclusions Clickable Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onNavigateToRouting() }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Apps,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Text(
                                        text = "Исключения приложений",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        text = "Пускать выбранные приложения в обход прокси (банки, госуслуги)",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // 3. Notifications Explanation Banner
            item {
                SettingsSectionTitle("Уведомления службы")
                Spacer(modifier = Modifier.height(6.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Зачем приложению нужны уведомления?",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Операционная система Android требует постоянное уведомление для работы служб VPN и TProxy. Это гарантирует, что система не остановит соединение при блокировке экрана, в фоне или при экономии заряда.",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Показывать скорость в уведомлении",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Switch(
                                checked = settings.showSpeedInNotification,
                                onCheckedChange = { viewModel.updateShowSpeedInNotification(it) }
                            )
                        }
                    }
                }
            }

            // 4. Appearance Card
            item {
                SettingsSectionTitle("Оформление")
                Spacer(modifier = Modifier.height(6.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Цвета Material You", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text("Адаптивные цвета обоев Android", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = settings.dynamicColor,
                                onCheckedChange = { viewModel.updateTheme(it, settings.darkTheme, settings.amoledBlack) }
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Темная тема", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Switch(
                                checked = settings.darkTheme,
                                onCheckedChange = { viewModel.updateTheme(settings.dynamicColor, it, settings.amoledBlack) }
                            )
                        }

                        if (settings.darkTheme) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Глубокий черный (AMOLED)", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                    Text("Экономит заряд на OLED-экранах", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = settings.amoledBlack,
                                    onCheckedChange = { viewModel.updateTheme(settings.dynamicColor, settings.darkTheme, it) }
                                )
                            }
                        }
                    }
                }
            }

            // 5. Advanced Settings (Expandable)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { isAdvancedExpanded = !isAdvancedExpanded }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(12.dp))
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
                                modifier = Modifier.padding(top = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Port Configuration
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { showPortsDialog = true }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("Сетевые порты", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                        Text(
                                            "Mixed: ${settings.mixedPort} • TProxy: ${settings.tproxyPort} • DNS: ${settings.dnsPort}",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                // IPv6
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Поддержка IPv6", fontSize = 14.sp)
                                    Switch(
                                        checked = settings.ipv6Enabled,
                                        onCheckedChange = { viewModel.updateIpv6Enabled(it) }
                                    )
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                // Mihomo Core Section
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("Ядро Mihomo", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                        Text("Версия: ${state.coreVersion}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    FilledTonalButton(
                                        onClick = { viewModel.checkForCoreUpdate() },
                                        enabled = !state.isCheckingUpdate && !state.isDownloadingUpdate
                                    ) {
                                        if (state.isCheckingUpdate) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        } else {
                                            Text("Проверить", fontSize = 12.sp)
                                        }
                                    }
                                }

                                if (state.latestRelease != null && state.latestRelease?.tag_name != state.coreVersion) {
                                    Button(
                                        onClick = { viewModel.installCoreUpdate() },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !state.isDownloadingUpdate
                                    ) {
                                        if (state.isDownloadingUpdate) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                                        } else {
                                            Text("Обновить ядро до ${state.latestRelease?.tag_name}")
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

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                // Logs button
                                OutlinedButton(
                                    onClick = onNavigateToLogs,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Открыть системный журнал (Logs)")
                                }
                            }
                        }
                    }
                }
            }

            // 6. About App Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Mihomet v1.0", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "Клиент прокси нового поколения на базе ядра Mihomo с поддержкой Root TProxy, Non-Root VPN и Material 3.",
                            fontSize = 12.sp,
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
    }
}

@Composable
fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
    )
}

@Composable
fun ModeSelectRow(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onSelect() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = 15.sp
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
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
        title = { Text("Настройка сетевых портов") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = mixedPort,
                    onValueChange = { mixedPort = it },
                    label = { Text("Mixed Port (HTTP/SOCKS5)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = tproxyPort,
                    onValueChange = { tproxyPort = it },
                    label = { Text("TProxy Port (TCP/UDP)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = dnsPort,
                    onValueChange = { dnsPort = it },
                    label = { Text("DNS Listen Port") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = controllerPort,
                    onValueChange = { controllerPort = it },
                    label = { Text("REST API Controller Port") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        mixedPort.toIntOrNull() ?: currentSettings.mixedPort,
                        tproxyPort.toIntOrNull() ?: currentSettings.tproxyPort,
                        dnsPort.toIntOrNull() ?: currentSettings.dnsPort,
                        controllerPort.toIntOrNull() ?: currentSettings.controllerPort
                    )
                }
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
