package org.mihomet.app.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
    val state by viewModel.uiState.collectAsState()
    val settings = state.settings
    val context = LocalContext.current

    var showPortsDialog by remember { mutableStateOf(false) }
    var showLatencyDialog by remember { mutableStateOf(false) }
    var showDnsDialog by remember { mutableStateOf(false) }
    var showSubscriptionUaDialog by remember { mutableStateOf(false) }
    var showSubscriptionHwidDialog by remember { mutableStateOf(false) }
    var showMtuDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var isAdvancedExpanded by rememberSaveable { mutableStateOf(false) }

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
                .statusBarsPadding()
                .padding(innerPadding)
                .padding(horizontal = D2.ScreenH),
            verticalArrangement = Arrangement.spacedBy(D2.SectionGap),
            contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp)
        ) {
            // Large-title header on the Edition 2 language.
            item {
                D2ScreenTitle(text = stringResource(R.string.nav_settings))
            }

            // 1. Connection Mode Grouped Card
            item {
                ConnectionModeSection(settings, viewModel)
            }

            // 2. Routing & App Rules Card
            item {
                RoutingSection(settings, viewModel, onNavigateToRouting, onNavigateToSiteRules)
            }

            // 3. Subscriptions identity card (global UA / x-hwid merge defaults;
            //    per-profile custom headers override these — see Profiles screen)
            item {
                SubscriptionsSection(
                    settings = settings,
                    viewModel = viewModel,
                    onShowUaDialog = { showSubscriptionUaDialog = true },
                    onShowHwidDialog = { showSubscriptionHwidDialog = true }
                )
            }

            // 4. Notifications Card
            item {
                NotificationsSection(settings, viewModel)
            }

            // 4. Appearance Card
            item {
                AppearanceSection(
                    settings = settings,
                    viewModel = viewModel,
                    onShowLanguageDialog = { showLanguageDialog = true }
                )
            }

            // 5. Advanced Settings (Expandable)
            item {
                AdvancedSection(
                    state = state,
                    viewModel = viewModel,
                    expanded = isAdvancedExpanded,
                    onExpandedChange = { isAdvancedExpanded = it },
                    onShowPortsDialog = { showPortsDialog = true },
                    onShowLatencyDialog = { showLatencyDialog = true },
                    onShowDnsDialog = { showDnsDialog = true },
                    onShowMtuDialog = { showMtuDialog = true },
                    onNavigateToLogs = onNavigateToLogs
                )
            }

            // 6. Manufacturer diagnostics card (known OEM ROMs only)
            if (oemBrand != OemHints.OemBrand.STOCK) {
                item {
                    OemDiagnosticsSection(oemBrand, oemLaunch, oemLabel)
                }
            }

            // 7. About App Card
            item {
                AboutSection()
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
