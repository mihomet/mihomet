package org.mihomet.app.core.firewall

import com.topjohnwu.superuser.Shell
import org.mihomet.app.data.preferences.PerAppMode

interface FirewallEngine {
    val name: String

    fun setup(
        tproxyPort: Int,
        perAppMode: PerAppMode,
        appUids: Set<Int>,
        ipv6Enabled: Boolean
    ): Result<Unit>

    fun teardown(ipv6Enabled: Boolean): Result<Unit>
}

/**
 * Resolves the active default network interface (Wi-Fi / mobile data) that the
 * fwmark-666 core routing rules must point at. Probed through the root shell;
 * falls back to [FALLBACK] when detection fails.
 *
 * Extracted from the firewall engines so the command builders stay pure and
 * unit-testable: [detect] runs in setup() at runtime, never in tests.
 */
internal object DefaultTableProbe {
    const val FALLBACK = "wlan0"

    fun detect(): String {
        // Android keeps a per-network policy rule at pref 31000 pointing at the active
        // interface table; fall back to the main-table default route, then to wlan0.
        val fromRule = Shell.cmd("ip rule show | grep -E '^31000:' | awk '{print \$NF}'")
            .exec().out.firstOrNull()?.trim().orEmpty()
        if (fromRule.isNotEmpty()) return fromRule

        val fromRoute = Shell.cmd("ip route show table main | grep default | awk '{print \$5}'")
            .exec().out.firstOrNull()?.trim().orEmpty()
        if (fromRoute.isNotEmpty()) return fromRoute

        return FALLBACK
    }
}
