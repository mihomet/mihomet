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
import kotlinx.coroutines.launch
import org.mihomet.app.R
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
    }

    private var speedJob: Job? = null
    private var screenStateReceiver: BroadcastReceiver? = null
    private var isScreenOn = true

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

    fun build(text: String, ongoing: Boolean = true): Notification {
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

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.vpn_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_proxy_tile)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_proxy_tile, context.getString(R.string.action_disconnect), stopIntent)
            .setOngoing(ongoing)
            // Show the foreground notification immediately instead of after the system delay (API 27+)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun update(text: String, ongoing: Boolean = true) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, build(text, ongoing))
    }

    fun startSpeedUpdates() {
        speedJob?.cancel()
        val baseText = baseText ?: return
        speedJob = scope.launch {
            apiClientProvider.current().streamTraffic().collect { sample ->
                update(
                    "$baseText · ↓ ${formatSpeed(sample.down)} ↑ ${formatSpeed(sample.up)}"
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
        return when {
            mb >= 1.0 -> context.getString(R.string.speed_mb_per_s, mb)
            kb >= 1.0 -> context.getString(R.string.speed_kb_per_s, kb)
            else -> context.getString(R.string.speed_b_per_s, bytesPerSec)
        }
    }
}
