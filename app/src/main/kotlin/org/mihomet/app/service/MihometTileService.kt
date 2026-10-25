package org.mihomet.app.service

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.mihomet.app.core.root.RootShellManager
import org.mihomet.app.core.root.RootTProxyService
import org.mihomet.app.core.vpn.MihometVpnService
import org.mihomet.app.data.preferences.SettingsRepository
import org.mihomet.app.ui.MainActivity

@RequiresApi(Build.VERSION_CODES.N)
class MihometTileService : TileService(), KoinComponent {

    private val settingsRepository: SettingsRepository by inject()
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val settings = settingsRepository.settingsFlow.first()
            val isRunning = RootShellManager.isDaemonRunning()

            if (settings.isRootMode) {
                if (isRunning) {
                    RootTProxyService.stop(this@MihometTileService)
                } else {
                    RootTProxyService.start(this@MihometTileService)
                }
            } else {
                if (isRunning) {
                    MihometVpnService.stop(this@MihometTileService)
                } else {
                    val vpnIntent = VpnService.prepare(this@MihometTileService)
                    if (vpnIntent != null) {
                        // Needs user approval in UI
                        val activityIntent = Intent(this@MihometTileService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            val pendingIntent = android.app.PendingIntent.getActivity(
                                this@MihometTileService,
                                0,
                                activityIntent,
                                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            )
                            startActivityAndCollapse(pendingIntent)
                        } else {
                            @Suppress("StartActivityAndCollapseDeprecated", "DEPRECATION")
                            startActivityAndCollapse(activityIntent)
                        }
                        return@launch
                    }
                    MihometVpnService.start(this@MihometTileService)
                }
            }
            updateTileState()
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isRunning = RootShellManager.isDaemonRunning()

        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isRunning) "Mihomet (Active)" else "Mihomet"
        tile.updateTile()
    }
}
