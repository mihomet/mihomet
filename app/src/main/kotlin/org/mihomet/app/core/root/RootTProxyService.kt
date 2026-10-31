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
import kotlinx.coroutines.withTimeout
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

        /** Max automatic daemon restarts after a crash before giving up (VPN watchdog parity). */
        private const val MAX_DAEMON_RESTARTS = 3

        /** Total budget for the core REST API readiness gate after the daemon spawns. */
        private const val CORE_READINESS_TIMEOUT_MS = 15_000L

        /** Per-probe cap so a hanging /version call cannot stretch the readiness budget. */
        private const val CORE_READINESS_PROBE_TIMEOUT_MS = 2_000L

        /** Interval between core REST API readiness probes. */
        private const val CORE_READINESS_INTERVAL_MS = 500L

        /** Uptime (with reachable API) the daemon must reach before the restart counter resets. */
        private const val CORE_STABILITY_DELAY_MS = 30_000L

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
    private val apiClientProvider: org.mihomet.app.data.api.MihomoApiClientProvider by inject()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var daemonMonitorJob: kotlinx.coroutines.Job? = null

    /**
     * True while a user-initiated stop is in progress; a start in its readiness
     * gate checks this flag and aborts instead of resurrecting the running state
     * after the teardown already happened (VPN service parity).
     */
    @Volatile
    private var isStopping = false

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
                // Set synchronously (not inside the coroutine): a readiness probe
                // in flight checks this flag and aborts at once instead of racing
                // the queued stopProxy() below (VPN service parity).
                isStopping = true
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
        // A fresh start clears a stale stop flag (VPN parity): a queued user STOP
        // still runs right after this start and wins (it is the last user action).
        isStopping = false
        // Set once the daemon PID exists: any failure after this point (firewall
        // setup error, readiness timeout) must roll the daemon and the modified
        // system settings back instead of leaving a zombie session (audit F11).
        var rootDaemonStarted = false
        try {
            // 0. Recover Private DNS if the previous root session crashed before
            // stopDaemon() could restore it (.prev_dns exists while daemon is dead)
            RootShellManager.maybeRestorePrivateDns()
            RootShellManager.maybeRestoreOplusChains()

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
                // Consistency (external audit F13): the last-good YAML was generated
                // under the settings of a PREVIOUS session — tproxy-port, dns.listen,
                // controller port/secret may have changed since — while the firewall
                // in step 5 is built from the CURRENT settings. Re-run the last-good
                // content through the ConfigGenerator (tunFd = null → root mode) so
                // every app-managed mandatory key is rebuilt on top of it from the
                // current settings: tproxy-port/redir-port/routing-mark,
                // external-controller + secret, dns.listen + nameservers,
                // allow-lan/bind-address, mode/log-level — the core listeners and the
                // netfilter rules can then never disagree on ports. Root mode also
                // strips mixed-port, matching the normal start path. The override
                // patch is deliberately NOT applied: it belongs to the broken profile
                // and may be the very thing that broke it. If even the last-good
                // content cannot be regenerated (corrupted file), fail closed with
                // the original error — no daemon, no firewall rules.
                try {
                    ConfigGenerator.generateRuntimeConfig(
                        profileYamlContent = lastGoodYaml,
                        settings = settings,
                        tunFd = null
                    )
                } catch (regenError: Exception) {
                    android.util.Log.e(
                        "RootTProxy",
                        "Last known-good config is unusable; failing the start",
                        regenError
                    )
                    throw e
                }
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
            rootDaemonStarted = true

            // 5. Setup netfilter firewall rules
            firewallManager.setup(
                engineType = settings.firewallEngine,
                tproxyPort = settings.tproxyPort,
                dnsPort = settings.dnsPort,
                perAppMode = settings.perAppMode,
                appUids = settings.selectedAppUids,
                ipv6Enabled = settings.ipv6Enabled
            ).getOrThrow()

            // 6. Readiness gate: a PID from nohup proves nothing — the core may die
            // on an invalid config moments later, and a spawned process alone must
            // not report "connected" (audit F11). Only a REST /version answer means
            // the daemon is actually usable.
            android.util.Log.d("RootTProxy", "Waiting for core readiness (REST API /version)")
            if (!awaitCoreReadiness()) {
                if (isStopping) {
                    // A user stop is in flight and owns the teardown — stay silent.
                    android.util.Log.d("RootTProxy", "Readiness aborted: stop pending")
                    return
                }
                android.util.Log.e(
                    "RootTProxy",
                    "Core did not answer the REST API within ${CORE_READINESS_TIMEOUT_MS / 1000}s — rolling back"
                )
                // Full rollback: daemon, firewall rules, Private DNS / OEM chains.
                rollbackRootStart()
                updateNotification(getString(R.string.root_error, getString(R.string.root_error_timeout)))
                return
            }
            if (isStopping) {
                // Core became ready but a stop was requested meanwhile — the
                // stopProxy() teardown owns the state now; do not resurrect it.
                android.util.Log.d("RootTProxy", "Core ready but stop pending — aborting start")
                return
            }

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
                    // F13: name the profile whose start failed, so the user sees
                    // which profile is actually serving traffic (the last-good one).
                    getString(R.string.root_last_good_used, profileLabel)
                } else {
                    getString(R.string.root_running, profileLabel)
                }
            )

            // Watchdog: the root daemon is a detached nohup process — if it crashes,
            // restart it with backoff (same policy as the VPN core watchdog); after
            // MAX_DAEMON_RESTARTS failed rounds — clean stop. The restart budget is
            // NOT renewed while the daemon merely stays alive: it must survive the
            // CORE_STABILITY_DELAY_MS window first, otherwise a flapping daemon
            // (briefly alive on every check) resets the counter and the budget
            // never exhausts (audit F11).
            daemonMonitorJob?.cancel()
            daemonMonitorJob = serviceScope.launch {
                var attempts = 0
                // Moment of the first alive-detection of the current daemon
                // generation; null while the daemon is considered unstable.
                var stableSinceMillis: Long? = null
                while (isActive && org.mihomet.app.core.ServiceState.isRunning.value) {
                    kotlinx.coroutines.delay(10_000)
                    if (RootShellManager.isDaemonRunning()) {
                        // First detection opens the stability window; only a daemon
                        // alive for CORE_STABILITY_DELAY_MS renews the budget.
                        val stableSince = stableSinceMillis
                            ?: System.currentTimeMillis().also { stableSinceMillis = it }
                        if (System.currentTimeMillis() - stableSince >= CORE_STABILITY_DELAY_MS) {
                            attempts = 0
                        }
                        continue
                    }
                    // Daemon gone: the stability window closes, a round is consumed.
                    stableSinceMillis = null
                    attempts++
                    if (attempts > MAX_DAEMON_RESTARTS) {
                        updateNotification(getString(R.string.root_error, "daemon keeps crashing"))
                        org.mihomet.app.core.ServiceState.stopService(org.mihomet.app.core.RunningService.ROOT)
                        break
                    }
                    updateNotification(getString(R.string.vpn_restarting))
                    kotlinx.coroutines.delay(2000L * attempts)
                    RootShellManager.startDaemon(CoreConstants.ROOT_CONFIG_PATH)
                        .onFailure { failure -> android.util.Log.e("RootTProxy", "daemon restart failed", failure) }
                    if (RootShellManager.isDaemonRunning()) {
                        // Fresh PID: reopen the stability window instead of resetting
                        // attempts immediately (a briefly alive daemon must not renew
                        // the restart budget).
                        stableSinceMillis = System.currentTimeMillis()
                    }
                }
            }
        } catch (e: Exception) {
            if (rootDaemonStarted) {
                // Rollback (audit F11): a failure after the daemon spawn (e.g. a
                // firewall setup error) must not leave a live daemon with modified
                // Private DNS / OEM chains and half-applied firewall rules behind.
                android.util.Log.e("RootTProxy", "Root start failed after daemon spawn — rolling back", e)
                rollbackRootStart()
            } else {
                ServiceState.stopService(org.mihomet.app.core.RunningService.ROOT)
            }
            updateNotification(getString(R.string.root_error, e.message ?: "null"))
            e.printStackTrace()
        }
    }

    /**
     * Polls the core REST API (`GET /version`) until it answers, a stop is
     * requested, or [CORE_READINESS_TIMEOUT_MS] elapses. Root mode has no TUN
     * fd to verify — a version answer alone proves the daemon is usable. The
     * controller host/port and secret come from [apiClientProvider], which
     * mirrors the settings the runtime config was generated with.
     */
    private suspend fun awaitCoreReadiness(): Boolean {
        val deadline = System.currentTimeMillis() + CORE_READINESS_TIMEOUT_MS
        while (!isStopping) {
            val answered = runCatching {
                withTimeout(CORE_READINESS_PROBE_TIMEOUT_MS) {
                    apiClientProvider.current().getVersion().isSuccess
                }
            }.getOrDefault(false)
            if (answered) return true
            if (System.currentTimeMillis() >= deadline) break
            kotlinx.coroutines.delay(CORE_READINESS_INTERVAL_MS)
        }
        return false
    }

    /**
     * Full rollback of a failed root start (audit F11): kill the daemon — its
     * stop command also restores Private DNS and the OEM DNS reject chains —
     * then a best-effort firewall teardown for rules a partial
     * [FirewallManager.setup] may already have applied, a belt-and-braces
     * restore of the system settings in case the daemon was already dead when
     * we got here, and a clean ServiceState flip with a widget refresh.
     */
    private fun rollbackRootStart() {
        runCatching { RootShellManager.stopDaemon() }
            .onFailure { android.util.Log.w("RootTProxy", "rollback: stopDaemon failed", it) }
        // teardown(false): the session's IPv6 setting does not matter on the
        // rollback path — clean both families, like the VPN start does.
        runCatching { firewallManager.teardown(false) }
            .onFailure { android.util.Log.w("RootTProxy", "rollback: firewall teardown failed", it) }
        runCatching { RootShellManager.maybeRestorePrivateDns() }
        runCatching { RootShellManager.maybeRestoreOplusChains() }
        ServiceState.stopService(org.mihomet.app.core.RunningService.ROOT)
        // Refresh home screen widgets — every teardown path goes through here.
        MihometWidgetProvider.updateAll(this@RootTProxyService)
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
        isStopping = true
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
