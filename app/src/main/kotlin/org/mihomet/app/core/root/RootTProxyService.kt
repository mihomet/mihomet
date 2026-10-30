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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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
import org.mihomet.app.service.MihometWidgetProvider
import org.mihomet.app.ui.MainActivity
import java.io.File

class RootTProxyService : Service(), KoinComponent {

    companion object {
        const val ACTION_START = "org.mihomet.app.action.ROOT_START"
        const val ACTION_STOP = "org.mihomet.app.action.ROOT_STOP"
        private const val CHANNEL_ID = "mihomet_root_tproxy_channel"
        private const val NOTIFICATION_ID = 1001

        /**
         * Completion signal for the most recent [stop] request (audit P1 §5a).
         * [stop] arms a fresh deferred before delivering ACTION_STOP; [stopProxy]
         * completes it once the teardown (firewall rules + root daemon) has fully
         * finished, so callers that must not race the teardown (mode switching in
         * DashboardViewModel) can await it. Null when no stop was requested yet.
         */
        @Volatile
        var teardownCompleted: CompletableDeferred<Unit>? = null
            private set

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
            // Arm the teardown signal before delivering the stop intent so that
            // awaiters always observe a non-null deferred after stop() returns.
            teardownCompleted = CompletableDeferred()
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
    private var daemonMonitorJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val notification = buildNotification(getString(R.string.root_notification_starting))
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
                // Snapshot the deferred armed by stop() so THIS teardown completes
                // exactly the deferred its requester is awaiting (a newer stop()
                // may have re-armed the volatile field in the meantime).
                val completion = teardownCompleted
                serviceScope.launch {
                    stopProxy(completion)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private suspend fun startProxy() {
        try {
            // 0. Recover Private DNS if the previous root session crashed before
            // stopDaemon() could restore it (.prev_dns exists while daemon is dead)
            RootShellManager.maybeRestorePrivateDns()

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
            // Fail-closed (P0): a broken profile YAML must never start the core with an
            // empty config (everything routed to DIRECT while UI shows "connected").
            // If a last known-good runtime config exists on the root filesystem from a
            // previous successful start, start the daemon with it and warn; otherwise
            // abort the start (no daemon, no firewall rules).
            val lastGoodConfigPath =
                CoreConstants.ROOT_CONFIG_PATH.replace("config.yaml", "config.lastgood.yaml")
            var profileBroken = false
            val runtimeYaml = try {
                ConfigGenerator.generateRuntimeConfig(
                    profileYamlContent = rawYaml,
                    settings = settings,
                    tunFd = null,
                    overridePatch = selectedProfile?.overridePatch ?: ""
                )
            } catch (e: IllegalStateException) {
                val lastGoodYaml = Shell.cmd("cat $lastGoodConfigPath 2>/dev/null").exec().out
                    .joinToString("\n")
                if (lastGoodYaml.isBlank()) throw e
                android.util.Log.w("RootTProxy", "Profile YAML is broken, starting with last known-good config", e)
                profileBroken = true
                lastGoodYaml
            }

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
            // Refresh home screen widgets (RemoteViews cannot observe the state flow).
            MihometWidgetProvider.updateAll(this@RootTProxyService)
            // Persist the mode as the autostart signal: the BootReceiver starts this
            // service on the next boot only when the daemon + firewall have started
            // successfully before (settings.autoStartOnBoot gates the feature).
            runCatching { settingsRepository.updateLastMode(org.mihomet.app.data.preferences.LastRunMode.ROOT) }
            // Refresh the last known-good runtime config on the root filesystem:
            // this exact config has just started successfully (daemon + firewall),
            // so it is safe to fall back to on a broken profile. Best-effort — a
            // failed copy must not fail the start.
            Shell.cmd("cp ${CoreConstants.ROOT_CONFIG_PATH} $lastGoodConfigPath 2>/dev/null || true").exec()
            val profileLabel = selectedProfile?.name ?: getString(R.string.dashboard_default_profile_name)
            updateNotification(
                if (profileBroken) {
                    getString(R.string.root_last_good_used)
                } else {
                    getString(R.string.root_running, profileLabel)
                }
            )

            // Watchdog: the root daemon is a detached nohup process — if it crashes,
            // restart it with backoff (same policy as the VPN core watchdog); after
            // 3 failed rounds — clean stop
            daemonMonitorJob?.cancel()
            daemonMonitorJob = serviceScope.launch {
                var attempts = 0
                while (isActive && org.mihomet.app.core.ServiceState.isRunning.value) {
                    kotlinx.coroutines.delay(10_000)
                    if (RootShellManager.isDaemonRunning()) {
                        attempts = 0
                        continue
                    }
                    attempts++
                    if (attempts > 3) {
                        updateNotification(getString(R.string.root_error, "daemon keeps crashing"))
                        org.mihomet.app.core.ServiceState.stopService(org.mihomet.app.core.RunningService.ROOT)
                        break
                    }
                    updateNotification(getString(R.string.vpn_restarting))
                    kotlinx.coroutines.delay(2000L * attempts)
                    RootShellManager.startDaemon(CoreConstants.ROOT_CONFIG_PATH)
                        .onFailure { failure -> android.util.Log.e("RootTProxy", "daemon restart failed", failure) }
                    if (RootShellManager.isDaemonRunning()) attempts = 0
                }
            }
        } catch (e: Exception) {
            ServiceState.stopService(org.mihomet.app.core.RunningService.ROOT)
            updateNotification(getString(R.string.root_error, e.message ?: "null"))
            e.printStackTrace()
        }
    }

    private suspend fun stopProxy(completion: CompletableDeferred<Unit>? = teardownCompleted) {
        // Teardown must never skip ServiceState.stopService: a failing root shell
        // (e.g. firewallManager.teardown throwing) would otherwise leave the UI
        // stuck on "connected" until the app is restarted.
        daemonMonitorJob?.cancel()
        daemonMonitorJob = null
        try {
            try {
                val settings = settingsRepository.settingsFlow.first()
                firewallManager.teardown(settings.ipv6Enabled)
            } finally {
                // Stop the daemon even if the firewall teardown failed
                RootShellManager.stopDaemon()
            }
        } catch (e: Exception) {
            android.util.Log.e("RootTProxyService", "Root proxy teardown failed", e)
        } finally {
            ServiceState.stopService(org.mihomet.app.core.RunningService.ROOT)
            // Refresh home screen widgets after the state flip.
            MihometWidgetProvider.updateAll(this@RootTProxyService)
            // Release awaiters even when the teardown failed: they proceed with a
            // best-effort torn-down state instead of blocking until a timeout.
            completion?.complete(Unit)
        }
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
                getString(R.string.root_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.root_channel_desc)
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
            .setContentTitle(getString(R.string.root_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_proxy_tile)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_proxy_tile, getString(R.string.action_disconnect), stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
