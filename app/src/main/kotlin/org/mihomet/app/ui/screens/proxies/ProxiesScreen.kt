package org.mihomet.app.ui.screens.proxies

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import org.mihomet.app.R
import org.mihomet.app.core.network.ConnectionTestResult
import org.mihomet.app.core.network.PingProtocol
import org.mihomet.app.ui.components.CountBadge
import org.mihomet.app.ui.components.ExpressiveLatencyBadge
import org.mihomet.app.ui.components.ExpressiveSnackbarHost
import org.mihomet.app.ui.components.ProtocolBadge
import org.mihomet.app.ui.components.SearchPill
import org.mihomet.app.ui.components.rememberPressScale
import org.mihomet.app.ui.design2.D2
import org.mihomet.app.ui.design2.D2IconAction
import org.mihomet.app.ui.design2.D2Panel
import org.mihomet.app.ui.design2.D2ScreenTitle
import org.mihomet.app.ui.design2.D2SectionLabel
import org.mihomet.app.ui.design2.D2SegmentOption
import org.mihomet.app.ui.design2.D2Segmented
import org.mihomet.app.ui.theme.*

/**
 * Servers screen on the "Edition 2" language: big display title with round
 * tonal quick actions, filled tonal node panels (selection = primaryContainer
 * tint, the only state allowed a border), no outlined cards anywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxiesScreen(
    viewModel: ProxiesViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current

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

    LaunchedEffect(state.statusMessageRes) {
        state.statusMessageRes?.let { resId ->
            val args = state.statusMessageArgs
            viewModel.clearStatusMessage()
            snackbarHostState.showSnackbar(
                context.getString(resId, *args.toTypedArray())
            )
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            viewModel.clearError()
            snackbarHostState.showSnackbar(context.getString(R.string.proxies_error_load, it), withDismissAction = true)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { ExpressiveSnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
                .padding(horizontal = D2.ScreenH)
        ) {
            // ── Header: big display title + round tonal quick actions ────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                D2ScreenTitle(
                    text = stringResource(R.string.nav_proxies),
                    modifier = Modifier.weight(1f)
                ) {
                    if (sortedServers.isNotEmpty()) {
                        CountBadge(count = sortedServers.size)
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    TestAllPill(
                        isTestingAll = state.isTestingAll,
                        enabled = !state.isTestingAll && state.allServers.isNotEmpty(),
                        onClick = {
                            AppHaptics.perform(context, HapticType.CLICK, view)
                            viewModel.testAllServers()
                        }
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    D2IconAction(
                        icon = Icons.Default.Tune,
                        contentDescription = stringResource(R.string.cd_proxies_tools),
                        onClick = {
                            AppHaptics.perform(context, HapticType.CLICK, view)
                            showToolsSheet = true
                        }
                    )
                }
            }

            // ── Group selection pills (if subscription has multiple groups) ──
            if (selectorGroups.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selectorGroups.forEach { group ->
                        val isGroupSelected = (currentGroup?.name ?: state.selectedGroupName) == group.name
                        Surface(
                            shape = D2.Pill,
                            color = if (isGroupSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            modifier = Modifier
                                .clip(D2.Pill)
                                .clickable {
                                    AppHaptics.perform(context, HapticType.CLICK, view)
                                    viewModel.selectGroup(group.name)
                                }
                        ) {
                            Text(
                                text = group.name,
                                fontSize = 12.sp,
                                fontWeight = if (isGroupSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                color = if (isGroupSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                            )
                        }
                    }
                }
            }

            // ── Search ───────────────────────────────────────────────────────
            SearchPill(
                value = state.searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                placeholderRes = R.string.proxies_search_hint,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
            )

            // ── Current connection live diagnostics panel (if tested) ────────
            state.currentConnectionResult?.let { result ->
                CurrentConnectionCard(
                    result = result,
                    onDismiss = { /* keep state or re-test */ },
                    onRetest = { viewModel.testCurrentConnection() },
                    modifier = Modifier.padding(top = 14.dp)
                )
            }

            // Loading state
            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (sortedServers.isEmpty()) {
                // Edition 2 empty state: one filled panel with icon + title + hint.
                D2Panel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp)
                    ) {
                        Surface(
                            shape = D2.Inner,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Dns,
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        Text(
                            text = if (state.searchQuery.isNotBlank()) {
                                stringResource(R.string.common_not_found)
                            } else {
                                stringResource(R.string.proxies_empty)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        if (state.searchQuery.isBlank()) {
                            Text(
                                text = stringResource(R.string.proxies_empty_hint),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
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

    // Expressive Tools & Settings Bottom Sheet
    if (showToolsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showToolsSheet = false },
            shape = D2.Hero,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
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
                Toast.makeText(context, context.getString(R.string.config_copied), Toast.LENGTH_SHORT).show()
            }
        )
    }
}

/** Filled "test all" pill for the title row: primaryContainer, springy squeeze. */
@Composable
private fun TestAllPill(
    isTestingAll: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interaction, pressedScale = 0.9f)
    Surface(
        shape = D2.Pill,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .scale(pressScale)
            .clip(D2.Pill)
            .clickable(interactionSource = interaction, enabled = enabled) { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            if (isTestingAll) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.proxies_pinging),
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
                    text = stringResource(R.string.proxies_test_all),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

/**
 * Server node panel on the Edition 2 language: filled tonal container
 * (selection morphs to a primaryContainer tint — the only state allowed a
 * border), squircle flag, protocol tag pill, ping capsule, spring check.
 */
@Composable
fun ExpressiveServerNodeCard(
    node: ServerNodeItem,
    isSelected: Boolean,
    isTesting: Boolean = false,
    onSelect: () -> Unit,
    onPingClick: () -> Unit
) {
    val cardInteractionSource = remember { MutableInteractionSource() }
    val cardPressScale = rememberPressScale(cardInteractionSource, pressedScale = 0.98f)

    val cardColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "nodeCardColor"
    )
    // Border is reserved for the selected state; it fades out otherwise.
    val cardBorderColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        } else {
            Color.Transparent
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "nodeCardBorder"
    )
    // Selection indicator: one circle that fills and springs its check in.
    val indicatorColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "nodeIndicatorColor"
    )
    val indicatorBorderColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "nodeIndicatorBorder"
    )
    val checkScale by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "nodeCheckScale"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scale(cardPressScale)
            .clip(D2.Container)
            .clickable(
                interactionSource = cardInteractionSource,
                onClick = onSelect
            ),
        shape = D2.Container,
        color = cardColor,
        border = BorderStroke(1.dp, cardBorderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Flag or Auto-select squircle icon
            Surface(
                shape = D2.Inner,
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

            // Node Name and Protocol (weighted column: latency chip can never
            // squeeze or overlap the name; both lines ellipsize, never cut mid-word)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(3.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Content-sized protocol tag: never wrapped, never truncated mid-word
                    ProtocolBadge(protocol = node.type)
                    if (node.network.isNotBlank()) {
                        Spacer(modifier = Modifier.width(4.dp))
                        ProtocolBadge(protocol = node.network.uppercase())
                    }

                    if (node.server.isNotBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${node.server}:${node.port}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Ping Badge (Clickable)
            ExpressiveLatencyBadge(
                delayMs = node.delayMs,
                isTesting = isTesting,
                onClick = onPingClick
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Selection indicator (animated fill + spring check)
            Surface(
                shape = CircleShape,
                color = indicatorColor,
                border = BorderStroke(1.5.dp, indicatorBorderColor),
                modifier = Modifier.size(22.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(R.string.cd_selected),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .size(14.dp)
                            .scale(checkScale)
                    )
                }
            }
        }
    }
}

/** Current-connection diagnostics panel: accent tint carries the verdict, no border. */
@Composable
fun CurrentConnectionCard(
    result: ConnectionTestResult,
    onDismiss: () -> Unit,
    onRetest: () -> Unit,
    modifier: Modifier = Modifier
) {
    D2Panel(
        modifier = modifier.fillMaxWidth(),
        color = if (result.isSuccess) {
            AccentGreen.copy(alpha = 0.14f)
        } else {
            AccentRed.copy(alpha = 0.14f)
        },
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = D2.Inner,
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
                    text = if (result.isSuccess) stringResource(R.string.connection_active) else stringResource(R.string.connection_error),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = if (result.isSuccess) {
                        stringResource(R.string.connection_active_details, result.ip, result.location, result.latencyMs)
                    } else {
                        result.errorMessage ?: stringResource(R.string.connection_failed)
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            D2IconAction(
                icon = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.cd_retry),
                onClick = onRetest
            )
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
    val context = LocalContext.current
    val view = LocalView.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = stringResource(R.string.proxies_tools_title),
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
                    AppHaptics.perform(context, HapticType.CLICK, view)
                    onTestAll()
                },
                modifier = Modifier.weight(1f),
                shape = D2.Container
            ) {
                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.proxies_ping_all), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            FilledTonalButton(
                onClick = {
                    AppHaptics.perform(context, HapticType.CLICK, view)
                    onTestCurrentConnection()
                },
                modifier = Modifier.weight(1f),
                shape = D2.Container
            ) {
                if (state.isTestingCurrentConnection) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.proxies_network_test), fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
        D2SectionLabel(text = stringResource(R.string.proxies_ping_method))

        D2Segmented(
            options = PingProtocol.values().map { proto ->
                D2SegmentOption(proto, proto.shortName)
            },
            selected = state.pingProtocol,
            onOptionSelected = { proto ->
                AppHaptics.perform(context, HapticType.CLICK, view)
                onSelectPingProtocol(proto)
            },
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

        // Sort Order
        D2SectionLabel(text = stringResource(R.string.proxies_sort_title))

        D2Segmented(
            options = ServerSortOrder.values().map { order ->
                D2SegmentOption(order, stringResource(order.titleRes))
            },
            selected = state.sortOrder,
            onOptionSelected = { order ->
                AppHaptics.perform(context, HapticType.CLICK, view)
                onSelectSortOrder(order)
            },
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

        // View Config Action
        FilledTonalButton(
            onClick = onOpenConfigViewer,
            modifier = Modifier.fillMaxWidth(),
            shape = D2.Container
        ) {
            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.proxies_view_config), fontWeight = FontWeight.SemiBold)
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
                Text(stringResource(R.string.proxies_config_title), fontWeight = FontWeight.Black, fontSize = 18.sp)
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
                    .clip(D2.Inner)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = displayContent.ifBlank { stringResource(R.string.proxies_config_empty) },
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
                shape = D2.Inner
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.action_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

fun getNodeFlagEmoji(nodeName: String): String {
    val name = nodeName.uppercase()
    // ISO codes must match as standalone tokens: otherwise "Australia" (AUS) matches "US"
    fun has(code: String) = Regex("(^|[^A-Z])$code([^A-Z]|$)").containsMatchIn(name)
    fun hasAny(vararg keys: String) = keys.any { name.contains(it) }
    return when {
        has("RU") || hasAny("РОССИЯ", "RUSSIA", "MOSCOW", "МОСКВА") -> "🇷🇺"
        has("NL") || hasAny("НИДЕРЛАНД", "NETHERLAND", "AMSTERDAM") -> "🇳🇱"
        has("DE") || hasAny("ГЕРМАНИ", "GERMANY", "FRANKFURT", "БЕРЛИН") -> "🇩🇪"
        has("US") || hasAny("USA", "UNITED STATES", "США", "AMERICA", "AMERIKA") -> "🇺🇸"
        has("GB") || has("UK") || hasAny("АНГЛИ", "LONDON", "ENGLAND", "БРИТАН") -> "🇬🇧"
        has("FI") || hasAny("ФИНЛЯНД", "FINLAND", "HELSINKI") -> "🇫🇮"
        has("SE") || hasAny("ШВЕЦИ", "SWEDEN", "STOCKHOLM") -> "🇸🇪"
        has("LV") || hasAny("ЛАТВИ", "LATVIA", "RIGA") -> "🇱🇻"
        has("PL") || hasAny("ПОЛЬШ", "POLAND", "WARSAW") -> "🇵🇱"
        has("FR") || hasAny("ФРАНЦИ", "FRANCE", "PARIS") -> "🇫🇷"
        has("HK") || hasAny("ГОНКОНГ", "HONG KONG", "HONGKONG") -> "🇭🇰"
        has("TR") || hasAny("ТУРЦИ", "TURKEY", "ISTANBUL") -> "🇹🇷"
        has("KZ") || hasAny("КАЗАХ", "KAZAKH") -> "🇰🇿"
        has("JP") || hasAny("ЯПОНИ", "JAPAN", "TOKYO") -> "🇯🇵"
        has("SG") || hasAny("СИНГАПУР", "SINGAPORE") -> "🇸🇬"
        has("AU") || hasAny("АВСТРАЛ", "AUSTRALIA", "SYDNEY") -> "🇦🇺"
        has("CA") || hasAny("КАНАД", "CANADA") -> "🇨🇦"
        hasAny("ЕВРОП", "EUROPE") || has("EU") -> "🇪🇺"
        else -> "🌐"
    }
}
