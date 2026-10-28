package org.mihomet.app.core.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import org.mihomet.app.ui.MainActivity
import java.io.File
import java.util.concurrent.TimeUnit

class MihometVpnService : VpnService(), KoinComponent {

    companion object {
        const val ACTION_START = "org.mihomet.app.action.VPN_START"
        const val ACTION_STOP = "org.mihomet.app.action.VPN_STOP"
        private const val CHANNEL_ID = "mihomet_vpn_channel"
        private const val NOTIFICATION_ID = 1002

        /** Max automatic core restarts after abnormal exit before giving up (CMFA/FlClash watchdog). */
        private const val MAX_CORE_RESTARTS = 3

        /** Backoff base for core restarts: delay = CORE_RESTART_BACKOFF_MS * attempt. */
        private const val CORE_RESTART_BACKOFF_MS = 2000L

        fun start(context: Context) {
            val intent = Intent(context, MihometVpnService::class.java).apply {
                action = ACTION_START
            }
            context.startService(intent)
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
    private val apiClientProvider: org.mihomet.app.data.api.MihomoApiClientProvider by inject()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var speedJob: Job? = null
    private var screenStateReceiver: BroadcastReceiver? = null
    private var isScreenOn = true
    private var speedBaseText: String? = null
    private val vpnMutex = Mutex()

    /** True while a user-initiated stop is in progress; blocks the core watchdog from restarting. */
    @Volatile
    private var isStopping = false

    /** Consecutive automatic core restart attempts since the last successful start. */
    @Volatile
    private var coreRestartAttempts = 0

    private var vpnInterface: ParcelFileDescriptor? = null
    private var mihomoProcess: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentActiveNetwork: Network? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val notification = buildNotification(getString(R.string.vpn_notification_starting))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                serviceScope.launch {
                    startVpn()
                }
            }
            ACTION_STOP -> {
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
                val notification = buildNotification(getString(R.string.vpn_notification_starting))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                serviceScope.launch {
                    startVpn()
                }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // VPN revoked by the user/system (Always-on off, another VPN chosen):
        // stop cleanly instead of leaving a zombie foreground state
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
            cleanupVpnResources()
            runCatching {
                if (org.mihomet.app.core.root.RootShellManager.isRootAvailable()) {
                    org.mihomet.app.core.root.RootShellManager.stopDaemon()
                }
            }
            android.util.Log.d("MihometVpn", "cleanupVpnResources() done")

            val settings = settingsRepository.settingsFlow.first()
            val selectedProfile = profileDao.getSelectedProfile()

            val rawYaml = if (selectedProfile != null && File(selectedProfile.filePath).exists()) {
                File(selectedProfile.filePath).readText()
            } else {
                assets.open("default_config.yaml").bufferedReader().use { it.readText() }
            }

            // "Settings" button in the system VPN dialog opens our app (CMFA/FlClash pattern)
            val configureIntent = PendingIntent.getActivity(
                this,
                2,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // Build TUN interface with 9000 MTU and non-blocking mode (matches CMFA standard for gVisor)
            val builder = Builder()
                .setSession("Mihomet")
                .setMtu(CoreConstants.TUN_MTU)
                .setBlocking(false)
                .setConfigureIntent(configureIntent)
                .addAddress(CoreConstants.TUN_IPV4_ADDR, CoreConstants.TUN_IPV4_PREFIX)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(CoreConstants.TUN_IPV4_DNS)

            // Always trap IPv6 into TUN interface to prevent carrier leakage / Happy Eyeballs hangs in Telegram
            builder.addAddress(CoreConstants.TUN_IPV6_ADDR, CoreConstants.TUN_IPV6_PREFIX)
            builder.addRoute("::", 0)
            builder.addDnsServer(CoreConstants.TUN_IPV6_DNS)

            // Do not mark VPN traffic as metered when underlying network is unmetered Wi-Fi (API 29+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            // Per-app split tunneling (VpnService does not allow mixing allowed and disallowed apps)
            val pm = packageManager
            when (settings.perAppMode) {
                org.mihomet.app.data.preferences.PerAppMode.WHITELIST -> {
                    // Only selected apps route through VPN; our own app is excluded by not being added
                    settings.selectedAppUids.forEach { uid ->
                        pm.getPackagesForUid(uid)?.forEach { pkg ->
                            runCatching { builder.addAllowedApplication(pkg) }
                        }
                    }
                }
                org.mihomet.app.data.preferences.PerAppMode.BLACKLIST -> {
                    // Exclude our own app plus selected blacklisted apps
                    builder.addDisallowedApplication(packageName)
                    settings.selectedAppUids.forEach { uid ->
                        pm.getPackagesForUid(uid)?.forEach { pkg ->
                            if (pkg != packageName) {
                                runCatching { builder.addDisallowedApplication(pkg) }
                            }
                        }
                    }
                }
                org.mihomet.app.data.preferences.PerAppMode.ALL -> {
                    // Exclude only our own app so core traffic doesn't loop
                    builder.addDisallowedApplication(packageName)
                }
            }

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

            // Clear FD_CLOEXEC so child process inherits the descriptor
            val fd = pfd.fd
            val fdObj = pfd.fileDescriptor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val currentFlags = Os.fcntlInt(fdObj, OsConstants.F_GETFD, 0)
                Os.fcntlInt(fdObj, OsConstants.F_SETFD, currentFlags and OsConstants.FD_CLOEXEC.inv())
            } else {
                runCatching {
                    val fcntlIntMethod = Os::class.java.getMethod("fcntlInt", java.io.FileDescriptor::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    val currentFlags = fcntlIntMethod.invoke(null, fdObj, OsConstants.F_GETFD, 0) as Int
                    fcntlIntMethod.invoke(null, fdObj, OsConstants.F_SETFD, currentFlags and OsConstants.FD_CLOEXEC.inv())
                }
            }

            // Pass fd 3 (duplicated from stdin via shell launcher)
            val runtimeYaml = ConfigGenerator.generateRuntimeConfig(
                profileYamlContent = rawYaml,
                settings = settings,
                tunFd = CoreConstants.TUN_FILE_DESCRIPTOR,
                overridePatch = selectedProfile?.overridePatch ?: ""
            )

            val vpnWorkDir = File(filesDir, "vpn_run").apply { mkdirs() }
            coreManager.ensureGeoDatabases(vpnWorkDir)
            val vpnConfigFile = File(vpnWorkDir, "config.yaml").apply {
                writeText(runtimeYaml)
            }
            val vpnLogFile = File(vpnWorkDir, "mihomo.log")

            val binaryPath = coreManager.getBundledLibraryPath()
            val pb = ProcessBuilder(
                "/system/bin/sh",
                "-c",
                "exec \"$binaryPath\" -d \"${vpnWorkDir.absolutePath}\" -f \"${vpnConfigFile.absolutePath}\" ${CoreConstants.TUN_FD_SHELL_REDIRECT}"
            ).redirectInput(ProcessBuilder.Redirect.INHERIT)
             .redirectErrorStream(true)
             .redirectOutput(ProcessBuilder.Redirect.appendTo(vpnLogFile))

            pb.environment()["HOME"] = vpnWorkDir.absolutePath
            pb.environment()["TMPDIR"] = cacheDir.absolutePath

            val savedStdin = runCatching { Os.dup(java.io.FileDescriptor.`in`) }.getOrNull()
            val proc = try {
                Os.dup2(fdObj, 0)
                pb.start()
            } finally {
                if (savedStdin != null) {
                    runCatching {
                        Os.dup2(savedStdin, 0)
                        Os.close(savedStdin)
                    }
                } else {
                    runCatching {
                        val devNull = Os.open("/dev/null", OsConstants.O_RDONLY, 0)
                        Os.dup2(devNull, 0)
                        Os.close(devNull)
                    }
                }
            }
            mihomoProcess = proc

            // Register NetworkCallback to handle WiFi <-> Mobile roaming dynamically
            registerNetworkCallback()

            serviceScope.launch {
                try {
                    val exitCode = proc.waitFor()
                    android.util.Log.d("MihometVpn", "Mihomo process exited with code $exitCode")
                    // Ownership check: if the user stopped (or a newer start replaced the process),
                    // this watchdog no longer owns the service state and must stay silent.
                    val watchdogOwnsProcess = mihomoProcess === proc && !isStopping
                    when {
                        exitCode == 0 -> {
                            ServiceState.stopService(org.mihomet.app.core.RunningService.VPN)
                        }
                        watchdogOwnsProcess && coreRestartAttempts < MAX_CORE_RESTARTS -> {
                            // Automatic restart with linear backoff (CMFA/FlClash watchdog pattern)
                            coreRestartAttempts++
                            val attempt = coreRestartAttempts
                            val tailLogs = runCatching { vpnLogFile.readLines().takeLast(10).joinToString("\n") }.getOrNull()
                            android.util.Log.w("MihometVpn", "Core exited with $exitCode, restarting (attempt $attempt/$MAX_CORE_RESTARTS): $tailLogs")
                            cleanupVpnResources()
                            updateNotification(getString(R.string.vpn_restarting))
                            delay(CORE_RESTART_BACKOFF_MS * attempt)
                            // User may have pressed Stop during the backoff delay, or started fresh.
                            // Serialize with user START/STOP so their action always wins.
                            vpnMutex.withLock {
                                if (!isStopping && mihomoProcess == null) {
                                    startVpnInternal()
                                }
                            }
                        }
                        watchdogOwnsProcess -> {
                            // Restart attempts exhausted — final failure
                            val tailLogs = runCatching { vpnLogFile.readLines().takeLast(10).joinToString("\n") }.getOrNull()
                            android.util.Log.e("MihometVpn", "Core exited with $exitCode, restarts exhausted: $tailLogs")
                            ServiceState.stopService(org.mihomet.app.core.RunningService.VPN)
                            updateNotification(getString(R.string.vpn_error_core, exitCode))
                        }
                        // else: user stop or a newer start owns the state — nothing to do
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.d("MihometVpn", "waitFor() canceled: ${e.message}")
                }
            }

            android.util.Log.d("MihometVpn", "Setting ServiceState.setRunning(VPN)")
            ServiceState.setRunning(org.mihomet.app.core.RunningService.VPN)
            // Successful start: stop is no longer pending and the restart backoff resets
            isStopping = false
            coreRestartAttempts = 0
            val profileLabel = selectedProfile?.name ?: getString(R.string.dashboard_default_profile_name)
            val baseText = getString(R.string.vpn_connected, profileLabel)
            speedBaseText = baseText
            updateNotification(baseText)

            registerScreenStateReceiver()

            // Live speed in the notification (optional toggle in settings).
            // Updates run only while the screen is on (FlClash pattern) — a 1 Hz
            // notification refresh in the pocket wastes battery for nothing.
            speedJob?.cancel()
            if (settings.showSpeedInNotification && isScreenOn) {
                startSpeedUpdates()
            }
        } catch (e: Exception) {
            android.util.Log.e("MihometVpn", "startVpn() exception", e)
            ServiceState.stopService(org.mihomet.app.core.RunningService.VPN)
            updateNotification(getString(R.string.vpn_error, e.message ?: "null"))
            e.printStackTrace()
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager = cm
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    android.util.Log.d("MihometVpn", "Network available: $network")
                    currentActiveNetwork = network
                    updateUnderlyingNetwork(network)
                }

                override fun onLost(network: Network) {
                    android.util.Log.d("MihometVpn", "Network lost: $network")
                    if (currentActiveNetwork == network) {
                        currentActiveNetwork = null
                        val fallback = cm.activeNetwork
                        updateUnderlyingNetwork(fallback)
                    }
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        !networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    ) {
                        currentActiveNetwork = network
                        updateUnderlyingNetwork(network)
                    }
                }
            }
            networkCallback = callback
            cm.registerNetworkCallback(request, callback)
            cm.activeNetwork?.let { updateUnderlyingNetwork(it) }
        } catch (e: Exception) {
            android.util.Log.w("MihometVpn", "Failed to register network callback: ${e.message}")
        }
    }

    private fun updateUnderlyingNetwork(network: Network?) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val networks = if (network != null) arrayOf(network) else null
                setUnderlyingNetworks(networks)
                android.util.Log.d("MihometVpn", "setUnderlyingNetworks updated: $network")
            }
            // Trigger connection flush so Mihomo drops stale sockets and reconnects on new interface
            serviceScope.launch {
                runCatching {
                    apiClientProvider.current().closeAllConnections()
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("MihometVpn", "updateUnderlyingNetwork error: ${e.message}")
        }
    }

    private fun cleanupVpnResources() {
        ServiceState.stopService(org.mihomet.app.core.RunningService.VPN)
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

        runCatching {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        }
        networkCallback = null

        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null

        screenStateReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenStateReceiver = null
    }

    private fun stopVpn() {
        android.util.Log.d("MihometVpn", "stopVpn() called")
        isStopping = true
        speedJob?.cancel()
        speedJob = null
        cleanupVpnResources()
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        val kb = bytesPerSec / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> getString(R.string.speed_mb_per_s, mb)
            kb >= 1.0 -> getString(R.string.speed_kb_per_s, kb)
            else -> getString(R.string.speed_b_per_s, bytesPerSec)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("MihometVpn", "MihometVpnService onDestroy() called!")
        stopVpn()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
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
            Intent(this, MihometVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_proxy_tile)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_proxy_tile, getString(R.string.action_disconnect), stopIntent)
            .setOngoing(true)
            // Show the foreground notification immediately instead of after the system delay (API 27+)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun startSpeedUpdates() {
        speedJob?.cancel()
        val baseText = speedBaseText ?: return
        speedJob = serviceScope.launch {
            apiClientProvider.current().streamTraffic().collect { sample ->
                updateNotification(
                    "$baseText · ↓ ${formatSpeed(sample.down)} ↑ ${formatSpeed(sample.up)}"
                )
            }
        }
    }

    /**
     * Pauses the 1 Hz speed updates while the screen is off (battery) and
     * resumes them on SCREEN_ON, restoring the plain connected text meanwhile.
     */
    private fun registerScreenStateReceiver() {
        if (screenStateReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        isScreenOn = true
                        val settings = runCatching {
                            kotlinx.coroutines.runBlocking { settingsRepository.settingsFlow.first() }
                        }.getOrNull()
                        if (settings?.showSpeedInNotification == true) startSpeedUpdates()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenOn = false
                        speedJob?.cancel()
                        speedJob = null
                        speedBaseText?.let { updateNotification(it) }
                    }
                }
            }
        }
        screenStateReceiver = receiver
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
