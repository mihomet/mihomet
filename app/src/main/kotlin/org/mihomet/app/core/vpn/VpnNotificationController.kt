package org.mihomet.app.core.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import org.mihomet.app.R
import org.mihomet.app.core.ServiceState
import org.mihomet.app.data.preferences.SettingsRepository
import org.mihomet.app.ui.MainActivity

/**
 * Owns everything user-visible about the VPN foreground notification:
 * channel creation, the notification itself, the optional 1 Hz live-speed
 * job (Settings → Notifications) and the screen on/off pause/resume receiver
 * (a 1 Hz notification refresh in the pocket wastes battery for nothing).
 *
 * Extracted verbatim from [MihometVpnService] — pure move, no behavior change.
 */
class VpnNotificationController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val apiClientProvider: org.mihomet.app.data.api.MihomoApiClientProvider,
    private val settingsRepository: SettingsRepository
) {

    companion object {
        const val CHANNEL_ID = "mihomet_vpn_channel"
        const val NOTIFICATION_ID = 1002
        const val REVOKED_NOTIFICATION_ID = 1003
    }

    private var speedJob: Job? = null
    private var screenStateReceiver: BroadcastReceiver? = null
    private var isScreenOn = true

    /** Active profile name displayed as the notification title. */
    var activeTitle: String? = null

    /** The connected / last-good text the speed line is appended to. */
    var baseText: String? = null

    fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun build(
        content: String,
        ongoing: Boolean = true,
        title: String = activeTitle ?: context.getString(R.string.vpn_notification_title),
        subText: String? = null
    ): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, MihometVpnService::class.java).apply { action = MihometVpnService.ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_proxy_tile)
            .setContentIntent(pendingIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            // Show the foreground notification immediately instead of after the system delay (API 27+)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (ongoing) {
            builder.addAction(R.drawable.ic_proxy_tile, context.getString(R.string.action_disconnect), stopIntent)
        }

        if (!subText.isNullOrBlank()) {
            builder.setSubText(subText)
        }

        return builder.build()
    }

    /**
     * One-shot notice when the tunnel was revoked externally (another VPN app
     * connected, Always-on switched away, …). Without it the disconnect looks
     * like a random drop: users running a second VPN client (Happ et al.) saw
     * the tunnel "reset" with no explanation.
     */
    fun showRevoked() {
        val text = context.getString(R.string.vpn_revoked_text)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.vpn_revoked_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_proxy_tile)
            .setContentIntent(pendingIntent)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        // POST_NOTIFICATIONS is user-granted (API 33+); the VPN foreground service
        // notifications bypass it, this one-shot notice does not.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        androidx.core.app.NotificationManagerCompat.from(context)
            .notify(REVOKED_NOTIFICATION_ID, notification)
    }

    fun update(
        content: String,
        ongoing: Boolean = true,
        title: String = activeTitle ?: context.getString(R.string.vpn_notification_title),
        subText: String? = null
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, build(content = content, ongoing = ongoing, title = title, subText = subText))
    }

    fun startSpeedUpdates() {
        speedJob?.cancel()
        if (!isScreenOn) return
        speedJob = scope.launch {
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            apiClientProvider.clientFlow.flatMapLatest { client ->
                client.streamTraffic()
            }.collect { sample ->
                val durationStr = ServiceState.connectionStartTime.value?.let { start ->
                    ServiceState.formatDuration(System.currentTimeMillis() - start)
                } ?: ""
                val speedText = "↓\u00A0${formatSpeed(sample.down)}   ↑\u00A0${formatSpeed(sample.up)}"
                update(
                    content = speedText,
                    subText = durationStr.ifBlank { null }
                )
            }
        }
    }

    fun stopSpeedUpdates() {
        speedJob?.cancel()
        speedJob = null
    }

    /** True while the screen is on (screen state receiver tracks it). */
    val screenOn: Boolean
        get() = isScreenOn

    /**
     * Pauses the 1 Hz speed updates while the screen is off (battery) and
     * resumes them on SCREEN_ON, restoring the plain connected text meanwhile.
     */
    fun registerScreenStateReceiver() {
        if (screenStateReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        isScreenOn = true
                        // Audit P2.2: broadcast receivers must not runBlocking
                        // (onReceive runs on the main thread). The settings read
                        // happens on the service scope; the notification state it
                        // guards is only refreshed a moment later.
                        scope.launch {
                            val settings = runCatching {
                                settingsRepository.settingsFlow.first()
                            }.getOrNull()
                            if (settings?.showSpeedInNotification == true) {
                                startSpeedUpdates()
                            }
                        }
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenOn = false
                        speedJob?.cancel()
                        speedJob = null
                        baseText?.let { update(it) }
                    }
                }
            }
        }
        screenStateReceiver = receiver
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    fun unregisterScreenStateReceiver() {
        screenStateReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        screenStateReceiver = null
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        val kb = bytesPerSec / 1024.0
        val mb = kb / 1024.0
        val formatted = when {
            mb >= 1.0 -> context.getString(R.string.speed_mb_per_s, mb)
            kb >= 1.0 -> context.getString(R.string.speed_kb_per_s, kb)
            else -> context.getString(R.string.speed_b_per_s, bytesPerSec)
        }
        return formatted.replace(' ', '\u00A0')
    }
}
