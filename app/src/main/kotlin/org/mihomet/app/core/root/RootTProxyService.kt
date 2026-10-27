package org.mihomet.app.core.root

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.mihomet.app.R
import org.mihomet.app.core.CoreConstants
import org.mihomet.app.core.CoreManager
import org.mihomet.app.core.ServiceState
import org.mihomet.app.core.config.ConfigGenerator
import org.mihomet.app.core.firewall.FirewallManager
import org.mihomet.app.data.db.ProfileDao
import org.mihomet.app.data.preferences.SettingsRepository
import org.mihomet.app.ui.MainActivity
import java.io.File

class RootTProxyService : Service(), KoinComponent {

    companion object {
        const val ACTION_START = "org.mihomet.app.action.ROOT_START"
        const val ACTION_STOP = "org.mihomet.app.action.ROOT_STOP"
        private const val CHANNEL_ID = "mihomet_root_tproxy_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, RootTProxyService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RootTProxyService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun restart(context: Context) {
            stop(context)
            start(context)
        }
    }

    private val profileDao: ProfileDao by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val coreManager: CoreManager by inject()
    private val firewallManager: FirewallManager by inject()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val notification = buildNotification("Starting Root TProxy...")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                serviceScope.launch {
                    startProxy()
                }
            }
            ACTION_STOP -> {
                serviceScope.launch {
                    stopProxy()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private suspend fun startProxy() {
        try {
            // 1. Bootstrap root binary if missing
            coreManager.bootstrapRootBinary().getOrThrow()

            // 2. Read active profile & settings
            val settings = settingsRepository.settingsFlow.first()
            val selectedProfile = profileDao.getSelectedProfile()

            val rawYaml = if (selectedProfile != null && File(selectedProfile.filePath).exists()) {
                File(selectedProfile.filePath).readText()
            } else {
                assets.open("default_config.yaml").bufferedReader().use { it.readText() }
            }

            // 3. Generate runtime config
            val runtimeYaml = ConfigGenerator.generateRuntimeConfig(
                profileYamlContent = rawYaml,
                settings = settings,
                tunFd = null
            )

            // Write to /data/adb/mihomet/run/config.yaml via root
            val writeCmd = "cat << 'EOF' > ${CoreConstants.ROOT_CONFIG_PATH}\n$runtimeYaml\nEOF"
            coreManager.ensureGeoDatabases(filesDir)
            val appGeoip = File(filesDir, "geoip.metadb")
            val appGeosite = File(filesDir, "geosite.dat")
            Shell.cmd(
                "mkdir -p ${CoreConstants.ROOT_RUN_DIR}",
                "if [ ! -f ${CoreConstants.ROOT_RUN_DIR}/geoip.metadb ] || [ ! -s ${CoreConstants.ROOT_RUN_DIR}/geoip.metadb ]; then cp \"${appGeoip.absolutePath}\" ${CoreConstants.ROOT_RUN_DIR}/geoip.metadb; fi",
                "if [ ! -f ${CoreConstants.ROOT_RUN_DIR}/geosite.dat ] || [ ! -s ${CoreConstants.ROOT_RUN_DIR}/geosite.dat ]; then cp \"${appGeosite.absolutePath}\" ${CoreConstants.ROOT_RUN_DIR}/geosite.dat; fi",
                "chmod 644 ${CoreConstants.ROOT_RUN_DIR}/geoip.metadb ${CoreConstants.ROOT_RUN_DIR}/geosite.dat 2>/dev/null || true",
                writeCmd
            ).exec()

            // 4. Start daemon
            RootShellManager.startDaemon(CoreConstants.ROOT_CONFIG_PATH).getOrThrow()

            // 5. Setup netfilter firewall rules
            firewallManager.setup(
                engineType = settings.firewallEngine,
                tproxyPort = settings.tproxyPort,
                dnsPort = settings.dnsPort,
                perAppMode = settings.perAppMode,
                appUids = settings.selectedAppUids,
                ipv6Enabled = settings.ipv6Enabled
            ).getOrThrow()

            ServiceState.setRunning(org.mihomet.app.core.RunningService.ROOT)
            updateNotification("Running (${selectedProfile?.name ?: "Default"})")
        } catch (e: Exception) {
            ServiceState.stopService(org.mihomet.app.core.RunningService.ROOT)
            updateNotification("Error: ${e.message}")
            e.printStackTrace()
        }
    }

    private suspend fun stopProxy() {
        val settings = settingsRepository.settingsFlow.first()
        firewallManager.teardown(settings.ipv6Enabled)
        RootShellManager.stopDaemon()
        ServiceState.stopService(org.mihomet.app.core.RunningService.ROOT)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.launch {
            try {
                stopProxy()
            } finally {
                serviceScope.cancel()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mihomet Root Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mihomet Root TProxy background status"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RootTProxyService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mihomet Root TProxy")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_proxy_tile)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_proxy_tile, "Disconnect", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
