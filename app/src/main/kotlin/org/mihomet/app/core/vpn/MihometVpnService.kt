package org.mihomet.app.core.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.mihomet.app.R
import org.mihomet.app.core.CoreManager
import org.mihomet.app.core.config.ConfigGenerator
import org.mihomet.app.data.db.ProfileDao
import org.mihomet.app.data.preferences.SettingsRepository
import org.mihomet.app.ui.MainActivity
import java.io.File

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
    private var vpnInterface: ParcelFileDescriptor? = null
    private var mihomoProcess: Process? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("Starting VPN..."))
                serviceScope.launch {
                    startVpn()
                }
            }
            ACTION_STOP -> {
                serviceScope.launch {
                    stopVpn()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun startVpn() {
        try {
            stopVpn()

            val settings = settingsRepository.settingsFlow.first()
            val selectedProfile = profileDao.getSelectedProfile()

            val rawYaml = if (selectedProfile != null && File(selectedProfile.filePath).exists()) {
                File(selectedProfile.filePath).readText()
            } else {
                assets.open("default_config.yaml").bufferedReader().use { it.readText() }
            }

            // Build TUN interface
            val builder = Builder()
                .setSession("Mihomet")
                .setMtu(9000)
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("172.19.0.2")
                // Bypass Mihomo subprocess and app traffic automatically
                .addDisallowedApplication(packageName)

            if (settings.ipv6Enabled) {
                builder.addAddress("fdfe:dcba:9876::1", 126)
                builder.addRoute("::", 0)
            }

            val pfd = builder.establish() ?: throw RuntimeException("Failed to establish VpnService")
            vpnInterface = pfd

            // Clear FD_CLOEXEC so child process inherits the descriptor
            val fd = pfd.fd
            val fdObj = pfd.fileDescriptor
            val currentFlags = Os.fcntlInt(fdObj, OsConstants.F_GETFD, 0)
            Os.fcntlInt(fdObj, OsConstants.F_SETFD, currentFlags and OsConstants.FD_CLOEXEC.inv())

            // Generate runtime config with tun fd
            val runtimeYaml = ConfigGenerator.generateRuntimeConfig(
                profileYamlContent = rawYaml,
                settings = settings,
                tunFd = fd
            )

            val vpnWorkDir = File(filesDir, "vpn_run").apply { mkdirs() }
            val vpnConfigFile = File(vpnWorkDir, "config.yaml").apply {
                writeText(runtimeYaml)
            }

            // Launch bundled native binary
            val binaryPath = coreManager.getBundledLibraryPath()
            val pb = ProcessBuilder(
                binaryPath,
                "-d", vpnWorkDir.absolutePath,
                "-f", vpnConfigFile.absolutePath
            ).redirectErrorStream(true)

            mihomoProcess = pb.start()

            updateNotification("Connected (${selectedProfile?.name ?: "Default"})")
        } catch (e: Exception) {
            updateNotification("VPN Error: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun stopVpn() {
        try {
            mihomoProcess?.destroy()
            mihomoProcess = null
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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
