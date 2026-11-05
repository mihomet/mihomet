package org.mihomet.app.core.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.mihomet.app.R
import org.mihomet.app.core.CoreConstants
import org.mihomet.app.core.CoreManager
import org.mihomet.app.core.ServiceState
import org.mihomet.app.core.config.ConfigGenerator
import org.mihomet.app.data.db.ProfileDao
import org.mihomet.app.data.preferences.SettingsRepository
import org.mihomet.app.data.subs.ProfileValidation
import org.mihomet.app.service.MihometWidgetProvider
import org.mihomet.app.ui.MainActivity
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Thin orchestrator for the non-root VPN mode: owns the service lifecycle and
 * the shared start/stop mutex, and delegates the individual responsibilities
 * to collaborators in this package — [TunBuilder] (VpnService.Builder
 * configuration incl. per-app split tunneling), [CoreProcessLauncher] (core
 * process spawn + fd 3 handoff), [CoreReadinessProbe] (REST /version gate),
 * [CoreWatchdog] (exit restarts with backoff), [VpnNotificationController]
 * (channel, notification, live speed) and [UnderlyingNetworkTracker]
 * (roaming / underlying networks).
 */
class MihometVpnService : VpnService(), KoinComponent, CoreWatchdog.Hooks {

    companion object {
        const val ACTION_START = "org.mihomet.app.action.VPN_START"
        const val ACTION_STOP = "org.mihomet.app.action.VPN_STOP"

        fun start(context: Context) {
            val intent = Intent(context, MihometVpnService::class.java).apply {
                action = ACTION_START
            }
            // startForegroundService (like RootTProxyService.start): the BootReceiver
            // starts the service from the background, where a plain startService()
            // throws IllegalStateException. The service always calls startForeground()
            // first thing in onStartCommand, so the FGS contract is never violated.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MihometVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val profileDao: ProfileDao by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val coreManager: CoreManager by inject()
    private val firewallManager: org.mihomet.app.core.firewall.FirewallManager by inject()
    private val apiClientProvider: org.mihomet.app.data.api.MihomoApiClientProvider by inject()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val vpnMutex = Mutex()

    private val notifications by lazy {
        VpnNotificationController(this, serviceScope, apiClientProvider, settingsRepository)
    }
    private val processLauncher by lazy { CoreProcessLauncher(this, coreManager) }
    private val readinessProbe by lazy { CoreReadinessProbe(apiClientProvider) }
    private val networkTracker by lazy { UnderlyingNetworkTracker(this, serviceScope, apiClientProvider) }
    private val coreWatchdog by lazy { CoreWatchdog(serviceScope, vpnMutex, hooks = this) }

    /** True while a user-initiated stop is in progress; blocks the core watchdog from restarting. */
    @Volatile
    private var isStopping = false

    private var vpnInterface: ParcelFileDescriptor? = null
    private var mihomoProcess: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundWithNotification()
                serviceScope.launch {
                    startVpn()
                }
            }
            ACTION_STOP -> {
                // Set synchronously (not inside the coroutine): a readiness probe
                // in flight checks this flag and releases the start mutex at once,
                // so the queued stopVpn() below never waits out the 15s budget.
                isStopping = true
                serviceScope.launch {
                    vpnMutex.withLock {
                        stopVpn()
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            // System restarted the service (Always-on VPN / START_STICKY path):
            // restore the tunnel from persisted settings, like CMFA does
            null -> {
                startForegroundWithNotification()
                serviceScope.launch {
                    startVpn()
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val notification = notifications.build(getString(R.string.vpn_notification_starting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                VpnNotificationController.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            startForeground(VpnNotificationController.NOTIFICATION_ID, notification)
        }
    }

    override fun onRevoke() {
        // VPN revoked by the user/system (Always-on off, another VPN chosen):
        // stop cleanly instead of leaving a zombie foreground state. Tell the
        // user WHY the tunnel dropped — the common case is a second VPN client
        // (Happ et al.) connecting, which silently kills our tunnel otherwise.
        runCatching { notifications.showRevoked() }
        isStopping = true
        serviceScope.launch {
            vpnMutex.withLock {
                stopVpn()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onRevoke()
    }

    /**
     * Mutex-serialized entry point for external start requests (ACTION_START).
     * The core watchdog restarts [startVpnInternal] directly once it has re-acquired
     * the mutex, so a user STOP/START issued during the backoff delay always wins.
     */
    private suspend fun startVpn() {
        vpnMutex.withLock {
            startVpnInternal()
        }
    }

    private suspend fun startVpnInternal() {
        android.util.Log.d("MihometVpn", "startVpnInternal() initiated")
        try {
            // A fresh start clears a stale stop flag: the mutex serializes us with
            // any queued user STOP, which still runs right after this start and
            // wins (it is the last user action). Without this reset the readiness
            // gate below would mistake the leftover flag for a stop during THIS start.
            isStopping = false
            cleanupVpnResources()
            runCatching {
                if (org.mihomet.app.core.root.RootShellManager.isRootAvailable()) {
                    org.mihomet.app.core.root.RootShellManager.stopDaemon()
                    // Clear leftover root firewall rules from a non-graceful root
                    // session (audit §5a): root session settings are unknown here,
                    // so teardown without IPv6
                    runCatching {
                        firewallManager.teardown(false)
                    }
                }
            }
            android.util.Log.d("MihometVpn", "cleanupVpnResources() done")

            val settings = settingsRepository.settingsFlow.first()
            val selectedProfile = profileDao.getSelectedProfile()

            // Fail-closed (audit N01-services): a missing file of the ALREADY
            // SELECTED profile must never be silently replaced with the bundled
            // default config — the tunnel would run a config the user never
            // chose. Only the first launch without any profile
            // (selectedProfile == null) may use the default. Abort with a
            // notification naming the profile, without starting the core.
            val rawYaml = if (selectedProfile != null) {
                val profileFile = File(selectedProfile.filePath)
                if (!profileFile.exists()) {
                    abortStart(getString(R.string.profile_file_missing, selectedProfile.name))
                    return
                }
                profileFile.readText()
            } else {
                assets.open("default_config.yaml").bufferedReader().use { it.readText() }
            }

            // Fail-closed (audit N01-services): validate the profile semantics
            // BEFORE the TUN interface and the core are set up — earlier and
            // clearer than the generator's own fail-closed check (W2).
            // Rules-only routing profiles (happ://routing) stay valid: the
            // validator accepts a non-empty `rules` list as a routing source.
            // (The bundled default config always passes — it ships non-empty
            // rules — so an invalid profile here is necessarily a selected one.)
            if (!ProfileValidation.isValid(rawYaml)) {
                val profileName = selectedProfile?.name
                    ?: getString(R.string.dashboard_default_profile_name)
                abortStart(getString(R.string.profile_invalid, profileName))
                return
            }

            // "Settings" button in the system VPN dialog opens our app (CMFA/FlClash pattern)
            val configureIntent = android.app.PendingIntent.getActivity(
                this,
                2,
                Intent(this, MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )

            val builder = TunBuilder(settings, packageName, packageManager)
                .configure(Builder(), configureIntent)

            val pfd = builder.establish() ?: throw RuntimeException("Failed to establish VpnService")
            vpnInterface = pfd

            // Acquire partial wake lock to prevent OEM OS background freezing (ColorOS, HyperOS, OneUI)
            runCatching {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mihomet:VpnWakeLock")?.apply {
                    setReferenceCounted(false)
                    acquire(24 * 60 * 60 * 1000L)
                }
            }

            val vpnRunDir = File(filesDir, "vpn_run")
            val lastGoodConfigFile = File(vpnRunDir, "config.lastgood.yaml")

            // Pass fd 3 (duplicated from stdin via shell launcher)
            // Fail-closed (P0): a broken profile YAML must never start the core with an
            // empty config (everything routed to DIRECT while UI shows "connected").
            // If a last known-good runtime config exists from a previous successful
            // start, start the core with it and warn; otherwise abort the start.
            var profileBroken = false
            val runtimeYaml = try {
                ConfigGenerator.generateRuntimeConfig(
                    profileYamlContent = rawYaml,
                    settings = settings,
                    tunFd = CoreConstants.TUN_FILE_DESCRIPTOR,
                    overridePatch = selectedProfile?.overridePatch ?: ""
                )
            } catch (e: IllegalStateException) {
                val lastGoodYaml = if (lastGoodConfigFile.isFile) {
                    runCatching { lastGoodConfigFile.readText() }.getOrNull()
                } else {
                    null
                }
                if (lastGoodYaml.isNullOrBlank()) throw e
                android.util.Log.w("MihometVpn", "Profile YAML is broken, starting with last known-good config", e)
                profileBroken = true
                // Consistency (external audit F13/N09, root-mode parity): the
                // last-good YAML was generated under the settings of a PREVIOUS
                // session — ports, DNS, controller secret may have changed since.
                // Re-run it through the ConfigGenerator so every app-managed
                // mandatory key is rebuilt on top of it from the CURRENT settings
                // (tunFd → VPN mode: tproxy keys stripped, tun block rebuilt) and
                // the core listeners can never disagree with the VpnService
                // builder. The override patch is deliberately NOT applied: it
                // belongs to the broken profile and may be the very thing that
                // broke it. If even the last-good content cannot be regenerated
                // (corrupted file), fail closed with the original error.
                try {
                    ConfigGenerator.generateRuntimeConfig(
                        profileYamlContent = lastGoodYaml,
                        settings = settings,
                        tunFd = CoreConstants.TUN_FILE_DESCRIPTOR
                    )
                } catch (regenError: Exception) {
                    android.util.Log.e(
                        "MihometVpn",
                        "Last known-good config is unusable; failing the start",
                        regenError
                    )
                    throw e
                }
            }

            val launch = processLauncher.launch(runtimeYaml, vpnRunDir, pfd)
            val proc = launch.process
            val vpnLogFile = launch.logFile
            mihomoProcess = proc

            // Register NetworkCallback to handle WiFi <-> Mobile roaming dynamically
            networkTracker.register()

            coreWatchdog.watch(proc, vpnLogFile)

            // Readiness gate: the controller port may already be bound while the
            // core is still booting, and a spawned process alone proves nothing —
            // only a /version answer means the core (TUN fd handed over, API up)
            // is actually usable. Until then the UI must NOT show "connected".
            android.util.Log.d("MihometVpn", "Waiting for core readiness (REST API /version)")
            if (!readinessProbe.await(proc) { isStopping || mihomoProcess !== proc }) {
                // Ownership check: a user stop or a newer start (watchdog restart)
                // owns the state now — stay silent, they are responsible for cleanup.
                if (isStopping || mihomoProcess !== proc) {
                    android.util.Log.d("MihometVpn", "Readiness aborted: stop pending or process replaced")
                    return
                }
                // Timed out: treat the start as failed instead of showing a
                // "connected" tunnel that was never proven to forward traffic.
                // The watchdog stays silent here: mihomoProcess is nulled by the
                // cleanup below, so the exit callback sees it no longer owns the
                // process, and the service is torn down right after.
                android.util.Log.e(
                    "MihometVpn",
                    "Core did not answer the REST API within ${CoreReadinessProbe.TIMEOUT_MS / 1000}s — failing start"
                )
                abortStart(getString(R.string.vpn_error_core_timeout))
                return
            }

            android.util.Log.d("MihometVpn", "Core ready — setting ServiceState.setRunning(VPN)")
            ServiceState.setRunning(org.mihomet.app.core.RunningService.VPN)
            // Refresh home screen widgets (RemoteViews cannot observe the state flow).
            MihometWidgetProvider.updateAll(this@MihometVpnService)
            // Persist the mode as the autostart signal: the BootReceiver starts this
            // service on the next boot only when the VPN has started successfully
            // before (settings.autoStartOnBoot gates the feature). Best-effort.
            runCatching { settingsRepository.updateLastMode(org.mihomet.app.data.preferences.LastRunMode.VPN) }
            // Refresh the last known-good runtime config: this exact file has just
            // started successfully, so it is safe to fall back to on a broken profile.
            runCatching { lastGoodConfigFile.writeText(runtimeYaml) }
            val profileLabel = selectedProfile?.name ?: getString(R.string.dashboard_default_profile_name)
            notifications.activeTitle = profileLabel
            val baseText = if (profileBroken) {
                // F13: name the profile whose start failed, so the user sees
                // which profile is actually serving traffic (the last-good one).
                getString(R.string.vpn_last_good_used, profileLabel)
            } else {
                getString(R.string.tile_connected)
            }
            notifications.baseText = baseText
            notifications.update(baseText)

            notifications.registerScreenStateReceiver()

            // Live speed in the notification (optional toggle in settings).
            // Updates run only while the screen is on (FlClash pattern) — a 1 Hz
            // notification refresh in the pocket wastes battery for nothing.
            notifications.stopSpeedUpdates()
            if (settings.showSpeedInNotification && notifications.screenOn) {
                notifications.startSpeedUpdates()
            }

            coreWatchdog.scheduleStabilityReset(proc)
        } catch (e: Exception) {
            android.util.Log.e("MihometVpn", "startVpn() exception", e)
            // Release whatever was already acquired (TUN interface, wake lock) so a
            // failed start (e.g. broken profile with no last known-good config)
            // does not leak resources until the next start attempt.
            cleanupVpnResources()
            ServiceState.stopService(org.mihomet.app.core.RunningService.VPN)
            notifications.update(getString(R.string.vpn_error, e.message ?: "null"))
            e.printStackTrace()
        }
    }

    /**
     * Fail-closed start abort (audit N01-services): the start was refused
     * before the core spawned — report why, release everything the aborted
     * start could hold, tear down the foreground state and stop the service
     * WITHOUT starting the core. Callers run with the start mutex held.
     * The notification is posted non-ongoing AFTER stopForeground(REMOVE),
     * so the system cannot tear it down together with the foreground state
     * and the user can dismiss it (same pattern as the readiness-fail path).
     */
    private fun abortStart(message: String) {
        cleanupVpnResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        notifications.update(message, ongoing = false)
    }

    // region CoreWatchdog.Hooks

    override fun currentProcess(): Process? = mihomoProcess

    override fun isStopping(): Boolean = isStopping

    override fun restartNotificationText(): String = getString(R.string.vpn_restarting)

    override fun giveUpNotificationText(exitCode: Int): String = getString(R.string.vpn_error_core, exitCode)

    override fun updateNotification(text: String, ongoing: Boolean) {
        notifications.update(text, ongoing)
    }

    override fun teardownForegroundService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override suspend fun restartCore() {
        startVpnInternal()
    }

    // endregion

    override fun cleanupVpnResources() {
        ServiceState.stopService(org.mihomet.app.core.RunningService.VPN)
        // Refresh home screen widgets — every teardown path goes through here.
        MihometWidgetProvider.updateAll(this@MihometVpnService)
        mihomoProcess?.let { proc ->
            proc.destroy()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching {
                    if (!proc.waitFor(1200, TimeUnit.MILLISECONDS)) {
                        proc.destroyForcibly()
                    }
                }
            }
        }
        mihomoProcess = null

        runCatching { vpnInterface?.close() }
        vpnInterface = null

        networkTracker.unregister()

        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null

        notifications.unregisterScreenStateReceiver()
    }

    private fun stopVpn() {
        android.util.Log.d("MihometVpn", "stopVpn() called")
        isStopping = true
        notifications.stopSpeedUpdates()
        cleanupVpnResources()
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("MihometVpn", "MihometVpnService onDestroy() called!")
        stopVpn()
        serviceScope.cancel()
    }
}
