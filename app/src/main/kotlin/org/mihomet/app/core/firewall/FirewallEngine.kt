package org.mihomet.app.core.firewall

import com.topjohnwu.superuser.Shell
import org.mihomet.app.core.CoreConstants
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

    companion object {
        /**
         * F10: the setup batches of both engines flip `net/ipv4/ip_forward` and
         * `conf/{all,default}/rp_filter` — historically without ever saving their
         * previous values, so a mihomet session permanently altered the device's
         * routing sysctls.
         *
         * [buildSysctlBackupCommands] snapshots the three values into
         * [CoreConstants.ROOT_SYSCTL_BACKUP_FILE] (one per line, in the order
         * below) before the first write of a session. The backup is gated on the
         * file's absence, so a crash mid-session can never overwrite the original
         * values with the already-modified ones — the next start keeps the stale
         * backup and the teardown below restores the true originals.
         *
         * [buildSysctlRestoreCommands] writes the values back and removes the
         * backup file. When no backup exists (setup never ran / nothing was
         * changed) it is a no-op — "if no backup, do not touch". Both builders
         * are appended verbatim to the engines' setup / teardown batches, so the
         * engines stay in sync by construction.
         */
        fun buildSysctlBackupCommands(): List<String> = buildList {
            val bak = CoreConstants.ROOT_SYSCTL_BACKUP_FILE
            add("mkdir -p ${CoreConstants.ROOT_RUN_DIR}")
            add(
                "if [ ! -f $bak ]; then " +
                        "cat /proc/sys/net/ipv4/ip_forward > $bak 2>/dev/null; " +
                        "cat /proc/sys/net/ipv4/conf/all/rp_filter >> $bak 2>/dev/null; " +
                        "cat /proc/sys/net/ipv4/conf/default/rp_filter >> $bak 2>/dev/null; fi"
            )
        }

        fun buildSysctlRestoreCommands(): List<String> = listOf(
            "if [ -f ${CoreConstants.ROOT_SYSCTL_BACKUP_FILE} ]; then " +
                    "IPFWD=\$(head -n 1 ${CoreConstants.ROOT_SYSCTL_BACKUP_FILE}); " +
                    "RPALL=\$(head -n 2 ${CoreConstants.ROOT_SYSCTL_BACKUP_FILE} | tail -n 1); " +
                    "RPDEF=\$(tail -n 1 ${CoreConstants.ROOT_SYSCTL_BACKUP_FILE}); " +
                    "echo \"\$IPFWD\" > /proc/sys/net/ipv4/ip_forward 2>/dev/null || true; " +
                    "echo \"\$RPALL\" > /proc/sys/net/ipv4/conf/all/rp_filter 2>/dev/null || true; " +
                    "echo \"\$RPDEF\" > /proc/sys/net/ipv4/conf/default/rp_filter 2>/dev/null || true; " +
                    "rm -f ${CoreConstants.ROOT_SYSCTL_BACKUP_FILE}; fi"
        )
    }
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
