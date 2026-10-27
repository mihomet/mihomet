package org.mihomet.app.core.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val vpnMutex = Mutex()
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
                val notification = buildNotification("Starting VPN...")
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
                    vpnMutex.withLock {
                        startVpn()
                    }
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
        }
        return START_NOT_STICKY
    }

    private suspend fun startVpn() {
        android.util.Log.d("MihometVpn", "startVpn() initiated")
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

            // Build TUN interface with 9000 MTU and non-blocking mode (matches CMFA standard for gVisor)
            val builder = Builder()
                .setSession("Mihomet")
                .setMtu(CoreConstants.TUN_MTU)
                .setBlocking(false)
                .addAddress(CoreConstants.TUN_IPV4_ADDR, CoreConstants.TUN_IPV4_PREFIX)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(CoreConstants.TUN_IPV4_DNS)

            // Always trap IPv6 into TUN interface to prevent carrier leakage / Happy Eyeballs hangs in Telegram
            builder.addAddress(CoreConstants.TUN_IPV6_ADDR, CoreConstants.TUN_IPV6_PREFIX)
            builder.addRoute("::", 0)
            builder.addDnsServer(CoreConstants.TUN_IPV6_DNS)

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
                tunFd = CoreConstants.TUN_FILE_DESCRIPTOR
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
                    if (exitCode != 0) {
                        val tailLogs = runCatching { vpnLogFile.readLines().takeLast(10).joinToString("\n") }.getOrNull()
                        android.util.Log.e("MihometVpn", "Core exited with $exitCode: $tailLogs")
                        ServiceState.stopService(org.mihomet.app.core.RunningService.VPN)
                        updateNotification("Ошибка ядра ($exitCode)")
                    } else {
                        ServiceState.stopService(org.mihomet.app.core.RunningService.VPN)
                    }
                } catch (e: Exception) {
                    android.util.Log.d("MihometVpn", "waitFor() canceled: ${e.message}")
                }
            }

            android.util.Log.d("MihometVpn", "Setting ServiceState.setRunning(VPN)")
            ServiceState.setRunning(org.mihomet.app.core.RunningService.VPN)
            updateNotification("Подключено (${selectedProfile?.name ?: "По умолчанию"})")
        } catch (e: Exception) {
            android.util.Log.e("MihometVpn", "startVpn() exception", e)
            ServiceState.stopService(org.mihomet.app.core.RunningService.VPN)
            updateNotification("Ошибка VPN: ${e.message}")
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
                    val apiClient = org.mihomet.app.data.api.MihomoApiClient(
                        host = CoreConstants.DEFAULT_CONTROLLER_HOST,
                        port = settingsRepository.settingsFlow.first().controllerPort,
                        secret = settingsRepository.settingsFlow.first().controllerSecret
                    )
                    apiClient.closeAllConnections()
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
    }

    private fun stopVpn() {
        android.util.Log.d("MihometVpn", "stopVpn() called")
        cleanupVpnResources()
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("MihometVpn", "MihometVpnService onDestroy() called!")
        stopVpn()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mihomet VPN Service",
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
            .setContentTitle("Mihomet VPN")
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
