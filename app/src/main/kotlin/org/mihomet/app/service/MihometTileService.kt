package org.mihomet.app.service

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.mihomet.app.R
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.mihomet.app.core.RunningService
import org.mihomet.app.core.ServiceState
import org.mihomet.app.core.root.RootTProxyService
import org.mihomet.app.core.vpn.MihometVpnService
import org.mihomet.app.data.preferences.SettingsRepository
import org.mihomet.app.ui.MainActivity

@RequiresApi(Build.VERSION_CODES.N)
class MihometTileService : TileService(), KoinComponent {

    private val settingsRepository: SettingsRepository by inject()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var listenJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listenJob?.cancel()
        listenJob = scope.launch {
            ServiceState.isRunning.collect { running ->
                updateTileState(running)
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        listenJob?.cancel()
        listenJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        listenJob?.cancel()
        scope.cancel()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val settings = settingsRepository.settingsFlow.first()
            val isRunning = ServiceState.isRunning.value

            if (isRunning) {
                if (settings.isRootMode || ServiceState.runningService.value == RunningService.ROOT) {
                    RootTProxyService.stop(this@MihometTileService)
                } else {
                    MihometVpnService.stop(this@MihometTileService)
                }
            } else {
                if (settings.isRootMode) {
                    RootTProxyService.start(this@MihometTileService)
                } else {
                    val vpnIntent = VpnService.prepare(this@MihometTileService)
                    if (vpnIntent != null) {
                        val activityIntent = Intent(this@MihometTileService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            val pendingIntent = PendingIntent.getActivity(
                                this@MihometTileService,
                                0,
                                activityIntent,
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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
            updateTileState(!isRunning)
        }
    }

    private fun updateTileState(isRunning: Boolean = ServiceState.isRunning.value) {
        val tile = qsTile ?: return
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isRunning) getString(R.string.tile_connected) else getString(R.string.tile_disconnected)
        }
        tile.updateTile()
    }
}
