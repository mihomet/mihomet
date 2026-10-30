package org.mihomet.app.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimization helpers (FlClash BatteryPlugin pattern).
 *
 * OEM power managers (MIUI/HyperOS, ColorOS, OneUI…) aggressively kill background
 * VPN services, which silently breaks the tunnel. On the first launch — ONCE per
 * install, gated by the [org.mihomet.app.data.preferences.AppSettings.battPromptShown]
 * DataStore flag — MainActivity offers to request the standard
 * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` exemption.
 */
object BatteryHelper {

    /** True when the system already whitelisted us from battery optimizations. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        // On failure assume "ignoring" — the fallback keeps the dialog from nagging.
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)
    }

    /** True on Xiaomi/Redmi/POCO devices (MIUI/HyperOS need an extra Autostart toggle). */
    fun isXiaomi(): Boolean = Build.MANUFACTURER.equals("xiaomi", ignoreCase = true)

    /**
     * Direct-request intent for the battery-optimization whitelist
     * (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, requires the manifest
     * permission `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). Returns null when the
     * activity is unavailable (custom ROMs) so the caller can fall back to the
     * general optimization list screen instead of crashing.
     */
    fun buildRequestIgnoreIntent(context: Context): Intent? {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        if (direct.resolveActivity(context.packageManager) != null) {
            return direct
        }
        // Fallback: the list of all battery-optimized apps (no special permission needed).
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        return if (list.resolveActivity(context.packageManager) != null) list else null
    }
}
