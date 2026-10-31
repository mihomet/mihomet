package org.mihomet.app.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import org.mihomet.app.R

/**
 * Builds an explicit-component intent for an OEM settings activity.
 *
 * Constructing a ComponentName for a not-installed package does NOT throw — the
 * intent is just a descriptor. Resolvability is checked later in
 * [OemHints.resolveLaunchable] via PackageManager. The runCatching only guards
 * exotic ROMs / unit-test stubs where the framework classes are unavailable.
 */
private fun componentIntent(pkg: String, cls: String): Intent? =
    runCatching { Intent().setComponent(ComponentName(pkg, cls)) }.getOrNull()

/**
 * OEM setup hints (FlClash BatteryPlugin pattern + don't-kill-my-app.com data).
 *
 * OEM power managers (MIUI/HyperOS, ColorOS/Realme UI, One UI, EMUI/HarmonyOS,
 * Funtouch OS) aggressively kill background VPN services even when the standard
 * battery-optimization exemption is granted. For known brands we can deep-link
 * the user straight into the vendor screen that controls autostart/background
 * restrictions instead of leaving them to hunt through Settings.
 *
 * Every candidate component is validated with PackageManager before launch:
 * an unresolvable component (older/never OS variant, app removed by the vendor)
 * simply falls through to the next candidate or the generic battery screen.
 */
object OemHints {

    /**
     * Known OEM brands with their vendor settings deep links.
     *
     * @param manufacturerPrefixes lowercase tokens matched with `contains`
     *   against both [Build.MANUFACTURER] and [Build.BRAND].
     * @param settingsIntent primary vendor deep link (autostart/startup manager
     *   where available — the setting OEMs most often reset silently).
     * @param notesRes short localized hint shown in the battery dialog.
     * @param altSettingsIntents fallback components tried in order when the
     *   primary one is not installed (vendor apps were renamed across OS
     *   generations, e.g. ColorOS safecenter → oplus.battery).
     */
    enum class OemBrand(
        val manufacturerPrefixes: List<String>,
        val settingsIntent: Intent?,
        val notesRes: Int?,
        val altSettingsIntents: List<Intent> = emptyList()
    ) {
        /** Xiaomi MIUI / HyperOS (Redmi, POCO). */
        XIAOMI(
            listOf("xiaomi", "redmi", "poco"),
            componentIntent(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            R.string.oem_xiaomi_note,
            listOfNotNull(
                componentIntent("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
            )
        ),

        /** OPPO ColorOS / realme UI / OnePlus OxygenOS (oplus family). */
        COLOROS(
            listOf("oppo", "realme", "oneplus", "oplus"),
            componentIntent(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            R.string.oem_coloros_note,
            listOfNotNull(
                componentIntent("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                componentIntent("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                componentIntent("com.oplus.battery", "com.oplus.battery.ui.BatteryActivity")
            )
        ),

        /** Samsung One UI (Device Care battery + sleeping apps). */
        SAMSUNG(
            listOf("samsung"),
            componentIntent(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.BatteryActivity"
            ),
            R.string.oem_samsung_note,
            listOfNotNull(
                componentIntent("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
            )
        ),

        /** Huawei EMUI / HarmonyOS and Honor Magic UI (startup manager). */
        HUAWEI(
            listOf("huawei", "honor"),
            componentIntent(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            R.string.oem_huawei_note,
            listOfNotNull(
                componentIntent(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
                )
            )
        ),

        /** vivo Funtouch OS / OriginOS and iQOO (background startup manager). */
        VIVO(
            listOf("vivo", "iqoo"),
            componentIntent(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ),
            R.string.oem_vivo_note,
            listOfNotNull(
                componentIntent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                componentIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")
            )
        ),

        /** Stock Android and unknown vendors — generic battery-optimization list. */
        STOCK(
            emptyList(),
            runCatching { Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }.getOrNull(),
            null
        );
    }

    /**
     * Pure brand detection from raw device strings — testable without a device.
     * Matching is lowercase `contains` over both manufacturer and brand, first
     * declared brand wins; anything unrecognized is [OemBrand.STOCK].
     */
    fun detect(manufacturer: String?, brand: String?): OemBrand {
        val m = manufacturer?.lowercase().orEmpty()
        val b = brand?.lowercase().orEmpty()
        return OemBrand.entries.firstOrNull { candidate ->
            candidate != OemBrand.STOCK &&
                candidate.manufacturerPrefixes.any { prefix -> m.contains(prefix) || b.contains(prefix) }
        } ?: OemBrand.STOCK
    }

    /** Detection for the real device ([Build.MANUFACTURER] / [Build.BRAND]). */
    fun detect(): OemBrand = detect(Build.MANUFACTURER, Build.BRAND)

    /**
     * Returns the first brand deep link whose settings activity actually exists
     * on this device, or null — the caller then shows the generic screen.
     * Never returns an intent for [OemBrand.STOCK].
     */
    fun resolveLaunchable(context: Context?, brand: OemBrand): Intent? {
        if (brand == OemBrand.STOCK) return null
        val pm = context?.packageManager ?: return null
        val candidates = listOfNotNull(brand.settingsIntent) + brand.altSettingsIntents
        return candidates.firstOrNull { intent ->
            runCatching { pm.resolveActivity(intent, 0) != null }.getOrDefault(false)
        }
    }

    /**
     * Like [resolveLaunchable], but falls back to the generic
     * `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` screen when the vendor
     * activity is unavailable (used by the Settings diagnostics card).
     */
    fun resolveLaunchableOrGeneric(context: Context?, brand: OemBrand): Intent? {
        resolveLaunchable(context, brand)?.let { return it }
        val pm = context?.packageManager ?: return null
        val generic = runCatching {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }.getOrNull() ?: return null
        return generic.takeIf {
            runCatching { pm.resolveActivity(it, 0) != null }.getOrDefault(false)
        }
    }
}
