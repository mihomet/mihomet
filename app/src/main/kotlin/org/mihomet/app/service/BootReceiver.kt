package org.mihomet.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.mihomet.app.core.root.RootTProxyService
import org.mihomet.app.core.vpn.MihometVpnService
import org.mihomet.app.data.preferences.LastRunMode
import org.mihomet.app.data.preferences.SettingsRepository

/**
 * Autostarts the proxy after the device boots (CMFA/FlClash pattern), gated by the
 * user's `autoStartOnBoot` setting and the last successfully started service mode:
 *
 * - [LastRunMode.VPN] → [MihometVpnService.start]. VPN consent CANNOT be granted at
 *   boot (no user interaction), so the start is skipped when the user never granted
 *   it before (Always-on VPN is off) — the service would otherwise fail silently.
 * - [LastRunMode.ROOT] → [RootTProxyService.start]. Root (KernelSU/Magisk) is
 *   available at boot; a missing grant degrades to an error notification.
 *
 * The receiver runs on the main thread — the DataStore read happens on IO inside
 * [goAsync], which extends the broadcast lifetime past onReceive and keeps the
 * 10s broadcast budget.
 */
class BootReceiver : BroadcastReceiver(), KoinComponent {

    private val settingsRepository: SettingsRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, ACTION_QUICKBOOT_POWERON -> Unit
            else -> return
        }

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                val settings = runCatching { settingsRepository.settingsFlow.first() }
                    .onFailure { android.util.Log.w(TAG, "Failed to read settings on boot", it) }
                    .getOrNull() ?: return@launch
                if (!settings.autoStartOnBoot) return@launch

                when (settings.lastMode) {
                    LastRunMode.VPN -> {
                        // VpnService.prepare returns null only when consent was already
                        // granted; at boot there is no UI to grant it — skip quietly.
                        if (VpnService.prepare(appContext) == null) {
                            runCatching { MihometVpnService.start(appContext) }
                                .onFailure { android.util.Log.w(TAG, "VPN autostart failed", it) }
                        } else {
                            android.util.Log.i(TAG, "VPN consent missing — autostart skipped")
                        }
                    }
                    LastRunMode.ROOT -> {
                        runCatching { RootTProxyService.start(appContext) }
                            .onFailure { android.util.Log.w(TAG, "Root TProxy autostart failed", it) }
                    }
                    // No successful start recorded yet — nothing to autostart.
                    else -> Unit
                }
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"

        /** Qualcomm/HTC quick-boot broadcast; no SDK constant exists. */
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
