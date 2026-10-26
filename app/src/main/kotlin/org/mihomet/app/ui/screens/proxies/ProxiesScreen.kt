package org.mihomet.app.ui.screens.proxies

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import org.mihomet.app.core.network.ConnectionTestResult
import org.mihomet.app.core.network.PingProtocol
import org.mihomet.app.ui.components.ProtocolBadge
import org.mihomet.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxiesScreen(
    viewModel: ProxiesViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    var showToolsSheet by remember { mutableStateOf(false) }
    var showConfigDialog by remember { mutableStateOf(false) }

    val selectorGroups = state.groups.filter { it.type == "select" || it.type == "selector" }
    val currentGroup = selectorGroups.find { it.name == state.selectedGroupName }
        ?: state.groups.find { it.name == state.selectedGroupName }
        ?: selectorGroups.firstOrNull()
        ?: state.groups.firstOrNull()
    val groupNodeNames = currentGroup?.nodeNames ?: emptyList()

    // Filter nodes belonging to active group (or all servers if no group filter)
    val groupServers = if (groupNodeNames.isNotEmpty()) {
        state.allServers.filter { groupNodeNames.contains(it.name) }
    } else {
        state.allServers
    }

    val searchFiltered = groupServers.filter {
        state.searchQuery.isBlank() ||
                it.name.contains(state.searchQuery, ignoreCase = true) ||
                it.server.contains(state.searchQuery, ignoreCase = true)
    }

    val sortedServers = when (state.sortOrder) {
        ServerSortOrder.DEFAULT -> searchFiltered.sortedByDescending { it.isAutoSelect }
        ServerSortOrder.BY_NAME -> searchFiltered.sortedWith(
            compareByDescending<ServerNodeItem> { it.isAutoSelect }
                .thenBy { it.name.lowercase() }
        )
        ServerSortOrder.BY_PING -> searchFiltered.sortedWith(
            compareByDescending<ServerNodeItem> { it.isAutoSelect }
                .thenBy {
                    when {
                        it.delayMs > 0 -> 0 // fast first
                        it.delayMs == 0L -> 1 // untested
                        else -> 2 // timeout (-1)
                    }
                }.thenBy { if (it.delayMs > 0) it.delayMs else Long.MAX_VALUE }
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Серверы",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black
                        )
                        if (sortedServers.isNotEmpty()) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Text(
                                    text = "${sortedServers.size}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                },
                actions = {
                    // Expressive Pill "Тест всех"
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(CircleShape)
                            .clickable(enabled = !state.isTestingAll && state.allServers.isNotEmpty()) {
                                AppHaptics.perform(context, HapticType.CLICK, view)
                                viewModel.testAllServers()
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            if (state.isTestingAll) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Пинг...",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            } else {
                                Icon(
                                    Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Тест всех",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    // Expressive Tune Button
                    Surface(
                        shape = ExpressiveShapes.Small,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        IconButton(
                            onClick = {
                                AppHaptics.perform(context, HapticType.CLICK, view)
                                showToolsSheet = true
                            },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = "Инструменты",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
            // Group Selection Pills (if subscription has multiple selector groups)
            if (selectorGroups.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selectorGroups.forEach { group ->
                        val isGroupSelected = (currentGroup?.name ?: state.selectedGroupName) == group.name
                        Surface(
                            shape = CircleShape,
                            color = if (isGroupSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(
                                1.dp,
                                if (isGroupSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    AppHaptics.perform(context, HapticType.CLICK, view)
                                    viewModel.selectGroup(group.name)
                                }
                        ) {
                            Text(
                                text = group.name,
                                fontSize = 12.sp,
                                fontWeight = if (isGroupSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                color = if (isGroupSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                            )
                        }
                    }
                }
            }

            // Expressive Search Pill
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    TextField(
                        value = state.searchQuery,
                        onValueChange = { viewModel.updateSearchQuery(it) },
                        placeholder = {
                            Text(
                                "Поиск сервера по имени или IP...",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    if (state.searchQuery.isNotBlank()) {
                        IconButton(
                            onClick = { viewModel.updateSearchQuery("") },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "Очистить",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Current Connection Live Diagnostics Card (if tested)
            state.currentConnectionResult?.let { result ->
                CurrentConnectionCard(
                    result = result,
                    onDismiss = { /* keep state or re-test */ },
                    onRetest = { viewModel.testCurrentConnection() }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Loading state
            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (sortedServers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (state.searchQuery.isNotBlank()) "Ничего не найдено" else "Нет доступных серверов",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    items(sortedServers, key = { it.name }) { node ->
                        val isSelected = node.name == state.selectedNodeName

                        ExpressiveServerNodeCard(
                            node = node,
                            isSelected = isSelected,
                            isTesting = state.testingNodeNames.contains(node.name),
                            onSelect = {
                                AppHaptics.perform(context, HapticType.CLICK, view)
                                viewModel.selectServer(currentGroup?.name ?: "", node)
                            },
                            onPingClick = {
                                AppHaptics.perform(context, HapticType.TICK, view)
                                viewModel.testSingleServer(node)
                            }
                        )
                    }
                }
            }
        }
    }

    // Incy-style Expressive Tools & Settings Bottom Sheet
    if (showToolsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showToolsSheet = false },
            shape = ExpressiveShapes.Hero,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            ExpressiveToolsSheetContent(
                state = state,
                onTestAll = {
                    showToolsSheet = false
                    viewModel.testAllServers()
                },
                onTestCurrentConnection = {
                    viewModel.testCurrentConnection()
                },
                onSelectPingProtocol = { viewModel.setPingProtocol(it) },
                onSelectSortOrder = { viewModel.setSortOrder(it) },
                onOpenConfigViewer = {
                    showToolsSheet = false
                    showConfigDialog = true
                }
            )
        }
    }

    // Configuration Viewer Dialog
    if (showConfigDialog) {
        ConfigViewerDialog(
            rawYaml = state.rawConfigYaml,
            jsonString = viewModel.getConfigAsFormattedJson(),
            onDismiss = { showConfigDialog = false },
            onCopy = { text ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Mihomet Config", text))
                Toast.makeText(context, "Конфигурация скопирована в буфер обмена", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

/**
 * Expressive Server Card with squircle flag, protocol tag pill, ping capsule, and active indicator.
 */
@Composable
fun ExpressiveServerNodeCard(
    node: ServerNodeItem,
    isSelected: Boolean,
    isTesting: Boolean = false,
    onSelect: () -> Unit,
    onPingClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ExpressiveShapes.Medium)
            .clickable(onClick = onSelect),
        shape = ExpressiveShapes.Medium,
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(
            1.dp,
            if (isSelected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Flag or Auto-select squircle icon
            Surface(
                shape = ExpressiveShapes.Small,
                color = if (node.isAutoSelect) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (node.isAutoSelect) {
                        Icon(
                            Icons.Default.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        Text(
                            text = getNodeFlagEmoji(node.name),
                            fontSize = 18.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Node Name and Protocol
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(3.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProtocolBadge(protocol = node.type)

                    if (node.server.isNotBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${node.server}:${node.port}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Ping Badge (Clickable)
            PingLatencyBadge(
                delayMs = node.delayMs,
                isTesting = isTesting,
                onClick = onPingClick
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Selection indicator
            if (isSelected) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Выбран",
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
}

@Composable
fun PingLatencyBadge(
    delayMs: Long,
    isTesting: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val (dotColor, textColor, text) = when {
        isTesting -> Triple(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary, "...")
        delayMs > 0 && delayMs < 120 -> Triple(AccentGreen, AccentGreenLight, "$delayMs мс")
        delayMs in 120..280 -> Triple(AccentOrange, AccentOrange, "$delayMs мс")
        delayMs > 280 -> Triple(AccentRed, AccentRed, "$delayMs мс")
        delayMs == -1L -> Triple(AccentRed, AccentRed, "Таймаут")
        else -> Triple(Color.Gray, MaterialTheme.colorScheme.onSurfaceVariant, "—")
    }

    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null && !isTesting,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, dotColor.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isTesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = dotColor
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = textColor
            )
        }
    }
}

@Composable
fun CurrentConnectionCard(
    result: ConnectionTestResult,
    onDismiss: () -> Unit,
    onRetest: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ExpressiveShapes.Medium,
        color = if (result.isSuccess) {
            AccentGreen.copy(alpha = 0.14f)
        } else {
            AccentRed.copy(alpha = 0.14f)
        },
        border = BorderStroke(
            1.dp,
            if (result.isSuccess) AccentGreen.copy(alpha = 0.4f) else AccentRed.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = ExpressiveShapes.Small,
                color = if (result.isSuccess) AccentGreen.copy(alpha = 0.2f) else AccentRed.copy(alpha = 0.2f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (result.isSuccess) Icons.Default.Public else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (result.isSuccess) AccentGreen else AccentRed,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (result.isSuccess) "Текущее соединение активно" else "Ошибка подключения",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = if (result.isSuccess) {
                        "IP: ${result.ip} • ${result.location} • ${result.latencyMs} ms"
                    } else {
                        result.errorMessage ?: "Сбой соединения"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onRetest) {
                Icon(Icons.Default.Refresh, contentDescription = "Повторить")
            }
        }
    }
}

@Composable
fun ExpressiveToolsSheetContent(
    state: ProxiesUiState,
    onTestAll: () -> Unit,
    onTestCurrentConnection: () -> Unit,
    onSelectPingProtocol: (PingProtocol) -> Unit,
    onSelectSortOrder: (ServerSortOrder) -> Unit,
    onOpenConfigViewer: () -> Unit
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Инструменты и диагностика",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black
        )

        // Diagnostics actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    onTestAll()
                },
                modifier = Modifier.weight(1f),
                shape = ExpressiveShapes.Medium
            ) {
                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Пинг всех", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            FilledTonalButton(
                onClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    onTestCurrentConnection()
                },
                modifier = Modifier.weight(1f),
                shape = ExpressiveShapes.Medium
            ) {
                if (state.isTestingCurrentConnection) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Тест сети", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Live connection test result inside bottom sheet
        state.currentConnectionResult?.let { result ->
            CurrentConnectionCard(
                result = result,
                onDismiss = {},
                onRetest = onTestCurrentConnection
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

        // Ping Protocol Selector
        Text(
            text = "Метод проверки пинга",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            PingProtocol.values().forEachIndexed { index, proto ->
                SegmentedButton(
                    selected = state.pingProtocol == proto,
                    onClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        onSelectPingProtocol(proto)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, PingProtocol.values().size)
                ) {
                    Text(proto.shortName, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

        // Sort Order
        Text(
            text = "Сортировка списка",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ServerSortOrder.values().forEach { order ->
                val isSelected = state.sortOrder == order
                Surface(
                    shape = CircleShape,
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onSelectSortOrder(order) }
                ) {
                    Text(
                        text = order.title,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

        // View Config Action
        OutlinedButton(
            onClick = onOpenConfigViewer,
            modifier = Modifier.fillMaxWidth(),
            shape = ExpressiveShapes.Medium
        ) {
            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Просмотр конфигурации (JSON / YAML)", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun ConfigViewerDialog(
    rawYaml: String,
    jsonString: String,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) } // 0 = JSON, 1 = YAML
    val displayContent = if (selectedTab == 0) jsonString else rawYaml

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Конфигурация", fontWeight = FontWeight.Black, fontSize = 18.sp)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.width(160.dp)) {
                    SegmentedButton(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                    ) {
                        Text("JSON", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    SegmentedButton(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) {
                        Text("YAML", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(350.dp)
                    .clip(ExpressiveShapes.Small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = displayContent.ifBlank { "Конфигурация пуста" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCopy(displayContent) },
                shape = ExpressiveShapes.Small
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Копировать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}

fun getNodeFlagEmoji(nodeName: String): String {
    val upper = nodeName.uppercase()
    return when {
        upper.contains("RU") || upper.contains("РОССИЯ") || upper.contains("RUSSIA") -> "🇷🇺"
        upper.contains("NL") || upper.contains("НИДЕРЛАНД") || upper.contains("NETHERLAND") -> "🇳🇱"
        upper.contains("DE") || upper.contains("ГЕРМАНИ") || upper.contains("GERMANY") -> "🇩🇪"
        upper.contains("US") || upper.contains("США") || upper.contains("AMERICA") -> "🇺🇸"
        upper.contains("GB") || upper.contains("UK") || upper.contains("АНГЛИ") || upper.contains("LONDON") -> "🇬🇧"
        upper.contains("FI") || upper.contains("ФИНЛЯНД") || upper.contains("FINLAND") -> "🇫🇮"
        upper.contains("SE") || upper.contains("ШВЕЦИ") || upper.contains("SWEDEN") -> "🇸🇪"
        upper.contains("LV") || upper.contains("ЛАТВИ") || upper.contains("LATVIA") -> "🇱🇻"
        upper.contains("PL") || upper.contains("ПОЛЬШ") || upper.contains("POLAND") -> "🇵🇱"
        upper.contains("FR") || upper.contains("ФРАНЦИ") || upper.contains("FRANCE") -> "🇫🇷"
        upper.contains("HK") || upper.contains("ГОНКОНГ") || upper.contains("HONG") -> "🇭🇰"
        upper.contains("TR") || upper.contains("ТУРЦИ") || upper.contains("TURKEY") -> "🇹🇷"
        upper.contains("KZ") || upper.contains("КАЗАХ") || upper.contains("KAZAKH") -> "🇰🇿"
        upper.contains("EU") || upper.contains("ЕВРОП") -> "🇪🇺"
        else -> "🌐"
    }
}
