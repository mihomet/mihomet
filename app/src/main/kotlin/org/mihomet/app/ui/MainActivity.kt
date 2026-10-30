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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
     * and plain shared text, queues the payload for the Profiles screen.
     * incy://connect|disconnect|toggle control the VPN directly (CMFA ExternalControl parity).
     */
    private fun handleImportIntent(intent: android.content.Intent?) {
        when (intent?.action) {
            android.content.Intent.ACTION_VIEW -> {
                intent.data?.toString()?.let { raw ->
                    handleControlLink(raw) ?: org.mihomet.app.core.DeepLinkParser.parse(raw)
                        ?.let { org.mihomet.app.core.DeepLinkBus.post(it) }
                }
            }
            android.content.Intent.ACTION_SEND -> {
                intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.let { text ->
                    org.mihomet.app.core.DeepLinkParser.parse(text)?.let { org.mihomet.app.core.DeepLinkBus.post(it) }
                }
            }
        }
    }

    private fun handleControlLink(raw: String): Boolean {
        val scheme = raw.substringBefore("://").lowercase()
        if (scheme != "incy" && scheme != "mihomet") return false
        val action = raw.substringAfter("://").substringBefore('?').substringBefore('/').lowercase()
        return when (action) {
            "connect", "start", "open" -> {
                lifecycleScope.launch { startPreferredMode() }; true
            }
            "disconnect", "stop", "close" -> {
                lifecycleScope.launch { stopActiveService() }; true
            }
            "toggle" -> {
                lifecycleScope.launch {
                    if (org.mihomet.app.core.ServiceState.isRunning.value) stopActiveService() else startPreferredMode()
                }; true
            }
            "status" -> true // handled silently: the caller reads state from the UI/notification
            "onadd", "autorouting", "autoroutingadd" -> {
                // Import the URL that follows the action; autorouting implies a refresh
                // schedule which mihomet derives from the panel's profile-update-interval
                val payload = raw.substringAfter('/', "")
                    .takeIf { it.startsWith("http") || it.isNotBlank() }
                if (!payload.isNullOrBlank()) {
                    org.mihomet.app.core.DeepLinkBus.post(payload)
                }
                true
            }
            else -> false
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
                val isXiaomi = BatteryHelper.isXiaomi()
                AlertDialog(
                    onDismissRequest = { showBatteryPrompt = false },
                    title = { Text(stringResource(R.string.batt_dialog_title)) },
                    text = {
                        Column {
                            Text(stringResource(R.string.batt_dialog_text))
                            if (isXiaomi) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.batt_xiaomi_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showBatteryPrompt = false
                                runCatching {
                                    BatteryHelper.buildRequestIgnoreIntent(this@MainActivity)
                                        ?.let { startActivity(it) }
                                }
                            }
                        ) { Text(stringResource(R.string.batt_open_settings)) }
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
