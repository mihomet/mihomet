package org.mihomet.app.ui.screens.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import org.mihomet.app.ui.components.D2BackHeader
import org.mihomet.app.ui.design2.D2Panel
import org.mihomet.app.ui.design2.D2Row
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.koin.androidx.compose.koinViewModel
import org.mihomet.app.R
import org.mihomet.app.core.OemHints
import org.mihomet.app.ui.components.ExpressiveSnackbarHost
import org.mihomet.app.ui.design2.D2
import org.mihomet.app.ui.design2.D2ScreenTitle

/**
 * Settings entry point: hosts the scaffold/snackbar/topbar, the LazyColumn of
 * section cards (each card lives in its own file in this package) and the
 * section dialogs. Pure composition only — every behavior belongs to
 * [SettingsViewModel] or the sections.
 */
@Composable
fun SettingsScreen(
    onNavigateToRouting: () -> Unit,
    onNavigateToSiteRules: () -> Unit = {},
    onNavigateToLogs: () -> Unit,
    onNavigateToConnections: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings
    val context = LocalContext.current

    var showPortsDialog by remember { mutableStateOf(false) }
    var showLatencyDialog by remember { mutableStateOf(false) }
    var showDnsDialog by remember { mutableStateOf(false) }
    var showSubscriptionUaDialog by remember { mutableStateOf(false) }
    var showSubscriptionHwidDialog by remember { mutableStateOf(false) }
    var showMtuDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var isAdvancedExpanded by rememberSaveable { mutableStateOf(true) }
    var section by rememberSaveable { mutableStateOf(-1) }
    BackHandler(section >= 0) { section = -1 }

    // Manufacturer diagnostics — only on devices from vendors whose power
    // managers are known to kill background VPN services (MIUI, ColorOS, …).
    val oemBrand = remember { OemHints.detect() }
    val oemLaunch = remember(oemBrand) { OemHints.resolveLaunchableOrGeneric(context, oemBrand) }
    val oemLabel = remember { Build.MANUFACTURER.replaceFirstChar { it.uppercase(Locale.ROOT) } }

    val snackbarHostState = remember { SnackbarHostState() }

    // Happ crypto keyring add outcome: snackbar on both results (the event id
    // makes every attempt re-trigger); the input fields are cleared by the
    // section itself on success only — see [HappCryptoKeysSection].
    LaunchedEffect(state.keyringEvent) {
        state.keyringEvent?.let { event ->
            snackbarHostState.showSnackbar(
                context.getString(event.messageRes),
                withDismissAction = !event.success
            )
            viewModel.clearKeyringEvent()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { ExpressiveSnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = D2.ScreenH),
            verticalArrangement = Arrangement.spacedBy(D2.SectionGap),
            contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp)
        ) {
            item {
                if (section < 0) D2ScreenTitle(stringResource(R.string.nav_settings))
                else D2BackHeader(stringResource(R.string.nav_settings), stringResource(R.string.cd_back), { section = -1 })
            }
            if (section < 0) {
                item {
                    D2Panel(contentPadding = PaddingValues(0.dp)) {
                        val categories = listOf(
                            Triple(R.string.settings_connection_mode, Icons.Default.VpnKey, R.string.settings_category_connection),
                            Triple(R.string.settings_routing_section, Icons.Default.Route, R.string.settings_category_routing),
                            Triple(R.string.settings_subs_section, Icons.Default.CloudDownload, R.string.settings_category_subscriptions),
                            Triple(R.string.settings_appearance, Icons.Default.Palette, R.string.settings_category_appearance),
                            Triple(R.string.settings_notifications, Icons.Default.Notifications, R.string.settings_category_notifications),
                            Triple(R.string.settings_advanced, Icons.Default.Tune, R.string.settings_category_advanced)
                        )
                        categories.forEachIndexed { index, category ->
                            D2Row(icon = category.second, title = stringResource(category.first), subtitle = stringResource(category.third),
                                onClick = { section = index; isAdvancedExpanded = true }) {
                                Icon(Icons.Default.ChevronRight, contentDescription = null)
                            }
                        }
                    }
                }
                item {
                    D2Panel(contentPadding = PaddingValues(0.dp)) {
                        D2Row(Icons.Default.Hub, stringResource(R.string.nav_connections), onClick = onNavigateToConnections) {
                            Icon(Icons.Default.ChevronRight, null)
                        }
                        D2Row(Icons.Default.Terminal, stringResource(R.string.nav_logs), onClick = onNavigateToLogs) {
                            Icon(Icons.Default.ChevronRight, null)
                        }
                    }
                }
                if (oemBrand != OemHints.OemBrand.STOCK) item { OemDiagnosticsSection(oemBrand, oemLaunch, oemLabel) }
                item { AboutSection() }
            } else item {
                when (section) {
                    0 -> ConnectionModeSection(settings, viewModel)
                    1 -> RoutingSection(settings, viewModel, onNavigateToRouting, onNavigateToSiteRules)
                    2 -> SubscriptionsSection(settings, viewModel, { showSubscriptionUaDialog = true }, { showSubscriptionHwidDialog = true })
                    3 -> AppearanceSection(settings, viewModel) { showLanguageDialog = true }
                    4 -> NotificationsSection(settings, viewModel)
                    5 -> AdvancedSection(
                        state, viewModel, isAdvancedExpanded, { isAdvancedExpanded = it },
                        { showPortsDialog = true }, { showLatencyDialog = true },
                        { showDnsDialog = true }, { showMtuDialog = true }, onNavigateToLogs
                    )
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

        if (showSubscriptionUaDialog) {
            SubscriptionUserAgentDialog(
                currentUa = settings.subscriptionUserAgent,
                onDismiss = { showSubscriptionUaDialog = false },
                onSave = { ua ->
                    viewModel.updateSubscriptionUserAgent(ua)
                    showSubscriptionUaDialog = false
                }
            )
        }

        if (showSubscriptionHwidDialog) {
            SubscriptionHwidDialog(
                currentHwid = settings.customHwid,
                onDismiss = { showSubscriptionHwidDialog = false },
                onSave = { hwid ->
                    viewModel.updateCustomHwid(hwid)
                    showSubscriptionHwidDialog = false
                }
            )
        }

        if (showMtuDialog) {
            TunMtuConfigDialog(
                currentMtu = settings.tunMtu,
                onDismiss = { showMtuDialog = false },
                onSave = { mtu ->
                    viewModel.updateTunMtu(mtu)
                    showMtuDialog = false
                }
            )
        }

        if (showLanguageDialog) {
            LanguageDialog(
                onDismiss = { showLanguageDialog = false }
            )
        }
    }
}
