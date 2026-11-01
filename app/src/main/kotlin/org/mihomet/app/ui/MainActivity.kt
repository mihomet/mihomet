package org.mihomet.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.mihomet.app.R
import org.mihomet.app.core.BatteryHelper
import org.mihomet.app.core.OemHints
import org.mihomet.app.data.preferences.SettingsRepository
import org.mihomet.app.ui.components.ExpressiveNavigationBar
import org.mihomet.app.ui.navigation.AppNavGraph
import org.mihomet.app.ui.navigation.bottomNavItems
import org.mihomet.app.ui.theme.MihometTheme

class MainActivity : ComponentActivity() {

    private val settingsRepository: SettingsRepository by inject()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleImportIntent(intent)
    }

    /**
     * Accepts one-tap import links (happ://, clash://, v2rayng://, sing-box://, incy:// …)
     * and plain shared text, queues the payload (with its best-effort source) for the
     * Profiles screen, where a confirmation dialog gates the actual import — nothing
     * is imported silently (audit P0 §1.1).
     *
     * Control links (incy://connect|disconnect|toggle, mihomet://start|stop|toggle,
     * CMFA ExternalControl parity) are gated by [DeepLinkPolicy]: only intents that
     * originate from this app's own PendingIntents may toggle the tunnel — browser
     * links and third-party apps are always rejected.
     */
    private fun handleImportIntent(intent: android.content.Intent?) {
        val origin = intentOrigin(intent)
        when (intent?.action) {
            android.content.Intent.ACTION_VIEW -> {
                intent.data?.toString()?.let { raw -> handleControlLink(raw, origin) }
            }
            android.content.Intent.ACTION_SEND -> {
                intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.let { text ->
                    org.mihomet.app.core.DeepLinkParser.parse(text)?.let {
                        org.mihomet.app.core.DeepLinkBus.post(it, describeSource())
                    }
                }
            }
        }
    }

    /**
     * Best-effort origin classification of the incoming intent (see
     * [org.mihomet.app.core.DeepLinkPolicy]). Doubt resolves to a non-SELF origin.
     */
    private fun intentOrigin(intent: android.content.Intent?): org.mihomet.app.core.DeepLinkPolicy.IntentOrigin {
        if (intent == null) return org.mihomet.app.core.DeepLinkPolicy.IntentOrigin.EXTERNAL_APP
        val browsable = intent.categories?.contains(android.content.Intent.CATEGORY_BROWSABLE) == true
        return org.mihomet.app.core.DeepLinkPolicy.resolveOrigin(
            browsable = browsable,
            selfPackage = packageName,
            referrer = referrer?.toString(),
            callingPackage = callingPackage
        )
    }

    /** Raw source description carried with import payloads into the confirm dialog. */
    private fun describeSource(): String? = referrer?.toString()

    private fun handleControlLink(raw: String, origin: org.mihomet.app.core.DeepLinkPolicy.IntentOrigin) {
        val scheme = raw.substringBefore("://").lowercase()
        if (scheme != "incy" && scheme != "mihomet") {
            org.mihomet.app.core.DeepLinkParser.parse(raw)?.let {
                org.mihomet.app.core.DeepLinkBus.post(it, describeSource())
            }
            return
        }
        val action = raw.substringAfter("://").substringBefore('?').substringBefore('/').lowercase()
        when {
            // Import verbs on the incy/mihomet URI space still go through the
            // pending-confirmation state — never a silent import.
            action == "onadd" || action == "autorouting" || action == "autoroutingadd" -> {
                val payload = raw.substringAfter('/', "")
                    .takeIf { it.startsWith("http") || it.isNotBlank() }
                if (!payload.isNullOrBlank()) {
                    org.mihomet.app.core.DeepLinkBus.post(payload, describeSource())
                }
            }
            org.mihomet.app.core.DeepLinkPolicy.isControlVerb(action) -> {
                when (org.mihomet.app.core.DeepLinkPolicy.decideControl(origin)) {
                    org.mihomet.app.core.DeepLinkPolicy.ControlDecision.ALLOW -> when (action) {
                        "connect", "start", "open" -> lifecycleScope.launch { startPreferredMode() }
                        "disconnect", "stop", "close" -> lifecycleScope.launch { stopActiveService() }
                        "toggle" -> lifecycleScope.launch {
                            if (org.mihomet.app.core.ServiceState.isRunning.value) stopActiveService() else startPreferredMode()
                        }
                        // Read-only no-op: the caller reads state from the UI/notification.
                        "status" -> {}
                    }
                    org.mihomet.app.core.DeepLinkPolicy.ControlDecision.REJECT ->
                        android.util.Log.w(
                            "MainActivity",
                            "Control link '$action' rejected: intent origin $origin is not SELF"
                        )
                }
            }
            // Unknown action — fall back to import parsing (confirmed before use).
            else -> org.mihomet.app.core.DeepLinkParser.parse(raw)?.let {
                org.mihomet.app.core.DeepLinkBus.post(it, describeSource())
            }
        }
    }

    private suspend fun startPreferredMode() {
        val settings = settingsRepository.settingsFlow.first()
        if (settings.isRootMode || org.mihomet.app.core.ServiceState.runningService.value == org.mihomet.app.core.RunningService.ROOT) {
            org.mihomet.app.core.root.RootTProxyService.start(this)
        } else {
            val vpnIntent = android.net.VpnService.prepare(this)
            if (vpnIntent != null) {
                startActivity(vpnIntent)
                return
            }
            org.mihomet.app.core.vpn.MihometVpnService.start(this)
        }
    }

    private suspend fun stopActiveService() {
        when (org.mihomet.app.core.ServiceState.runningService.value) {
            org.mihomet.app.core.RunningService.ROOT -> org.mihomet.app.core.root.RootTProxyService.stop(this)
            org.mihomet.app.core.RunningService.VPN -> org.mihomet.app.core.vpn.MihometVpnService.stop(this)
            else -> {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleImportIntent(intent)

        // Request POST_NOTIFICATIONS on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            val settings by settingsRepository.settingsFlow.collectAsState(initial = org.mihomet.app.data.preferences.AppSettings())
            val navController = rememberNavController()
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route

            val showBottomBar = bottomNavItems.any { it.route == currentRoute }

            MihometTheme(
                darkTheme = settings.darkTheme,
                dynamicColor = settings.dynamicColor,
                amoledBlack = settings.amoledBlack
            ) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        if (showBottomBar) {
                            ExpressiveNavigationBar(
                                items = bottomNavItems,
                                currentRoute = currentRoute,
                                onItemSelected = { screen ->
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                modifier = Modifier.navigationBarsPadding()
                            )
                        }
                    }
                ) { innerPadding ->
                    AppNavGraph(
                        navController = navController,
                        paddingValues = innerPadding
                    )
                }
            }

            // Battery-optimization prompt (FlClash BatteryPlugin pattern): shown at
            // most ONCE per install when the system does not already whitelist the
            // app from battery optimizations. The shown-flag is persisted the moment
            // the dialog is about to appear, so it never nags again — even if the
            // process is killed before the user answers.
            var showBatteryPrompt by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                val stored = settingsRepository.settingsFlow.first()
                showBatteryPrompt =
                    !stored.battPromptShown &&
                        !BatteryHelper.isIgnoringBatteryOptimizations(this@MainActivity)
                if (showBatteryPrompt) {
                    settingsRepository.updateBatteryPromptShown(true)
                }
            }
            if (showBatteryPrompt) {
                val oemBrand = remember { OemHints.detect() }
                val oemLaunch = remember(oemBrand) {
                    OemHints.resolveLaunchable(this@MainActivity, oemBrand)
                }
                AlertDialog(
                    onDismissRequest = { showBatteryPrompt = false },
                    title = { Text(stringResource(R.string.batt_dialog_title)) },
                    text = {
                        Column {
                            Text(stringResource(R.string.batt_dialog_text))
                            oemBrand.notesRes?.let { noteRes ->
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    stringResource(noteRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    confirmButton = {
                        Column(horizontalAlignment = Alignment.End) {
                            // Brand-specific deep link (MIUI Autostart, ColorOS Startup
                            // manager, …) — only offered when the vendor activity
                            // actually resolves on this device.
                            if (oemLaunch != null) {
                                TextButton(
                                    onClick = {
                                        showBatteryPrompt = false
                                        runCatching { startActivity(oemLaunch) }
                                    }
                                ) { Text(stringResource(R.string.batt_oem_button)) }
                            }
                            TextButton(
                                onClick = {
                                    showBatteryPrompt = false
                                    runCatching {
                                        BatteryHelper.buildRequestIgnoreIntent(this@MainActivity)
                                            ?.let { startActivity(it) }
                                    }
                                }
                            ) { Text(stringResource(R.string.batt_open_settings)) }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showBatteryPrompt = false }) {
                            Text(stringResource(R.string.batt_later))
                        }
                    }
                )
            }
        }
    }
}
