package org.mihomet.app.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.mihomet.app.R
import org.mihomet.app.core.RunningService
import org.mihomet.app.core.ServiceState
import org.mihomet.app.core.root.RootTProxyService
import org.mihomet.app.core.vpn.MihometVpnService
import org.mihomet.app.data.preferences.SettingsRepository
import org.mihomet.app.ui.MainActivity

/**
 * Classic (RemoteViews) home screen widget: brand icon + On/Off status + small
 * VPN/Root mode line. Tapping anywhere toggles the proxy exactly like
 * [MihometTileService.onClick] (start/stop the active or preferred mode).
 *
 * State refresh model (RemoteViews cannot observe Flows):
 * - [updatePeriodMillis] = 30 min periodic re-render (see res/xml/widget_mihomet_info.xml);
 * - [updateAll] is called right after the state flips inside MihometVpnService /
 *   RootTProxyService (start success + teardown) and after every toggle request.
 */
class MihometWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        // super first: dispatches APPWIDGET_UPDATE to onUpdate
        super.onReceive(context, intent)
        if (intent.action != ACTION_WIDGET_TOGGLE) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch {
            try {
                toggle(appContext)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Widget toggle failed", e)
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    /**
     * Toggle logic mirrored from [MihometTileService.onClick]. The widget cannot
     * show the VPN consent dialog — when consent is missing it silently skips the
     * start and opens [MainActivity] instead so the user can grant it there.
     */
    private suspend fun toggle(context: Context) {
        val settings = settingsRepository.settingsFlow.first()
        val isRunning = ServiceState.isRunning.value

        if (isRunning) {
            if (settings.isRootMode || ServiceState.runningService.value == RunningService.ROOT) {
                RootTProxyService.stop(context)
            } else {
                MihometVpnService.stop(context)
            }
        } else {
            if (settings.isRootMode) {
                RootTProxyService.start(context)
            } else {
                if (VpnService.prepare(context) != null) {
                    // No VPN consent yet — the widget cannot show the dialog.
                    context.startActivity(
                        Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                    )
                    return
                }
                MihometVpnService.start(context)
            }
        }
        updateAll(context)
    }

    companion object : KoinComponent {
        private const val TAG = "MihometWidget"

        const val ACTION_WIDGET_TOGGLE = "org.mihomet.app.action.WIDGET_TOGGLE"

        private val settingsRepository: SettingsRepository by inject()

        fun pendingToggleIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, MihometWidgetProvider::class.java).setAction(ACTION_WIDGET_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        /**
         * Re-renders every placed widget from the current [ServiceState]. Fire-and-forget:
         * reads the preferred mode from DataStore on IO (best-effort), then pushes new
         * RemoteViews via the thread-safe [AppWidgetManager] binder API. Called from the
         * toggle handler, the 30-min periodic update and both services after start/stop.
         */
        fun updateAll(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(appContext, MihometWidgetProvider::class.java))
            if (ids.isEmpty()) return

            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scope.launch {
                try {
                    val preferredRootMode = runCatching { settingsRepository.settingsFlow.first() }
                        .onFailure { android.util.Log.w(TAG, "Failed to read settings for widget", it) }
                        .getOrNull()
                        ?.isRootMode
                    val views = buildRemoteViews(appContext, preferredRootMode)
                    manager.updateAppWidget(ids, views)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Widget update failed", e)
                } finally {
                    scope.cancel()
                }
            }
        }

        /**
         * Status line: On (teal) / Off (grey). Mode line: the active service mode while
         * running, otherwise the preferred mode for the next start; empty when unknown.
         */
        private fun buildRemoteViews(context: Context, preferredRootMode: Boolean?): RemoteViews {
            val running = ServiceState.isRunning.value
            val activeMode = ServiceState.runningService.value
            val views = RemoteViews(context.packageName, R.layout.widget_mihomet)

            views.setTextViewText(
                R.id.widget_status,
                context.getString(if (running) R.string.widget_status_on else R.string.widget_status_off)
            )
            views.setTextColor(
                R.id.widget_status,
                context.getColor(if (running) R.color.widget_status_on else R.color.widget_status_off)
            )
            val modeText = when {
                running && activeMode == RunningService.ROOT -> context.getString(R.string.widget_mode_root)
                running -> context.getString(R.string.widget_mode_vpn)
                preferredRootMode == true -> context.getString(R.string.widget_mode_root)
                preferredRootMode == false -> context.getString(R.string.widget_mode_vpn)
                else -> ""
            }
            views.setTextViewText(R.id.widget_mode, modeText)
            views.setOnClickPendingIntent(R.id.widget_root, pendingToggleIntent(context))
            return views
        }
    }
}
