package org.mihomet.app.ui.screens.proxies

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import org.mihomet.app.ui.theme.AccentGreen
import org.mihomet.app.ui.theme.AccentOrange
import org.mihomet.app.ui.theme.AccentRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxiesScreen(
    viewModel: ProxiesViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    val selectorGroups = state.groups.filter {
        it.type.equals("select", ignoreCase = true) || it.type.equals("selector", ignoreCase = true)
    }
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

    val filteredServers = groupServers.filter {
        state.searchQuery.isBlank() ||
                it.name.contains(state.searchQuery, ignoreCase = true) ||
                it.server.contains(state.searchQuery, ignoreCase = true)
    }

    val autoSelectNodes = filteredServers.filter { it.isAutoSelect }
    val regularNodes = filteredServers.filter { !it.isAutoSelect }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Серверы", fontWeight = FontWeight.Bold) },
                actions = {
                    FilledTonalButton(
                        onClick = { viewModel.testAllServers() },
                        enabled = !state.isTestingAll && state.allServers.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        if (state.isTestingAll) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Пинг...", fontSize = 13.sp)
                        } else {
                            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Тест всех", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
            // Group Selection Chips (if subscription has multiple selector groups)
            if (selectorGroups.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selectorGroups.forEach { group ->
                        val isGroupSelected = (currentGroup?.name ?: state.selectedGroupName) == group.name
                        FilterChip(
                            selected = isGroupSelected,
                            onClick = { viewModel.selectGroup(group.name) },
                            label = { Text(group.name, fontWeight = if (isGroupSelected) FontWeight.Bold else FontWeight.Normal) },
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            }

            // Search Bar & Ping Mode Toggle Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Поиск по имени или адресу...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.searchQuery.isNotBlank()) {
                            IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Очистить")
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                    )
                )

                // Ping Mode Selector (HTTP vs TCP)
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.width(130.dp)
                ) {
                    SegmentedButton(
                        selected = state.pingMode == PingMode.HTTP,
                        onClick = { viewModel.setPingMode(PingMode.HTTP) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                    ) {
                        Text("HTTP", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    SegmentedButton(
                        selected = state.pingMode == PingMode.TCP,
                        onClick = { viewModel.setPingMode(PingMode.TCP) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) {
                        Text("TCP", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (state.statusMessage != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = state.statusMessage ?: "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.allServers.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = state.error ?: "Нет доступных серверов.\nДобавьте подписку во вкладке «Подписки».",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    // Subscription Auto-Select Cards
                    if (autoSelectNodes.isNotEmpty()) {
                        items(autoSelectNodes, key = { "auto_${it.name}" }) { node ->
                            val isSelected = state.selectedNodeName == node.name ||
                                    currentGroup?.selectedNode == node.name
                            AutoSelectCard(
                                node = node,
                                isSelected = isSelected,
                                pingMode = state.pingMode,
                                onSelect = { viewModel.selectServer(state.selectedGroupName, node) },
                                onTest = { viewModel.testSingleServer(node) }
                            )
                        }
                    }

                    // Section Header
                    if (regularNodes.isNotEmpty()) {
                        item(key = "header_all_servers") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (state.selectedGroupName.isNotBlank()) {
                                        "${state.selectedGroupName} (${regularNodes.size})"
                                    } else {
                                        "Все локации (${regularNodes.size})"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // Regular Servers
                    items(regularNodes, key = { it.name }) { node ->
                        val isSelected = state.selectedNodeName == node.name ||
                                currentGroup?.selectedNode == node.name
                        ServerRowCard(
                            node = node,
                            isSelected = isSelected,
                            pingMode = state.pingMode,
                            onSelect = { viewModel.selectServer(state.selectedGroupName, node) },
                            onTest = { viewModel.testSingleServer(node) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AutoSelectCard(
    node: ServerNodeItem,
    isSelected: Boolean,
    pingMode: PingMode,
    onSelect: () -> Unit,
    onTest: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        animationSpec = tween(250), label = "cardColor"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable { onSelect() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Bolt,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = node.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "АВТО",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Выбирает сервер с наименьшей задержкой",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            LatencyBadge(
                delayMs = node.delayMs,
                isTesting = node.isTesting,
                pingMode = pingMode,
                onClick = onTest
            )

            if (isSelected) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Выбран",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun ServerRowCard(
    node: ServerNodeItem,
    isSelected: Boolean,
    pingMode: PingMode,
    onSelect: () -> Unit,
    onTest: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface,
        animationSpec = tween(200), label = "rowColor"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onSelect() },
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.name,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (node.server.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${node.type} • ${node.server}:${node.port}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            LatencyBadge(
                delayMs = node.delayMs,
                isTesting = node.isTesting,
                pingMode = pingMode,
                onClick = onTest
            )

            if (isSelected) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Выбран",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun LatencyBadge(
    delayMs: Long,
    isTesting: Boolean = false,
    pingMode: PingMode = PingMode.HTTP,
    onClick: () -> Unit
) {
    val (text, color, bgColor) = when {
        isTesting -> Triple("...", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceVariant)
        delayMs > 0 && delayMs < 200 -> Triple("$delayMs ms", AccentGreen, AccentGreen.copy(alpha = 0.15f))
        delayMs in 200..600 -> Triple("$delayMs ms", AccentOrange, AccentOrange.copy(alpha = 0.15f))
        delayMs > 600 -> Triple("$delayMs ms", AccentRed, AccentRed.copy(alpha = 0.15f))
        delayMs == -1L -> Triple("Таймаут", AccentRed, AccentRed.copy(alpha = 0.15f))
        else -> Triple("Тест", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isTesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = text,
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
