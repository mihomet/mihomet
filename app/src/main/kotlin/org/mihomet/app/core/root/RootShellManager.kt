package org.mihomet.app.core.root

import com.topjohnwu.superuser.Shell
import org.mihomet.app.core.CoreConstants

object RootShellManager {

    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )
    }

    fun isRootAvailable(): Boolean {
        return runCatching {
            Shell.isAppGrantedRoot() == true ||
                    Shell.cmd("id").exec().out.any { it.contains("uid=0") }
        }.getOrDefault(false)
    }

    fun isDaemonRunning(): Boolean {
        return runCatching {
            val res = Shell.cmd(
                "if [ -f ${CoreConstants.ROOT_PID_FILE} ]; then " +
                        "PID=\$(cat ${CoreConstants.ROOT_PID_FILE}); " +
                        "if kill -0 \$PID 2>/dev/null; then echo ALIVE; else echo DEAD; fi; " +
                        "else echo DEAD; fi"
            ).exec()
            res.out.any { it.trim() == "ALIVE" }
        }.getOrDefault(false)
    }

    fun startDaemon(configPath: String): Result<Int> = runCatching {
        // Stop any running instance first
        stopDaemon()

        // 1. OnePlus / Realme / ColorOS Android 16 fix: snapshot the custom OEM
        //    DNS reject chains to save files, then flush them for the daemon
        //    lifetime. Without the snapshot the OEM rules would be lost forever;
        //    stopDaemon / [maybeRestoreOplusChains] replay them afterwards.
        Shell.cmd(*buildOplusFlushCommands().toTypedArray()).exec()

        // 2. Save and turn off Android Private DNS (prevents DoT port 853 validation failures)
        Shell.cmd(
            "PREV_DNS=\$(settings get global private_dns_mode 2>/dev/null); " +
            "if [ -n \"\$PREV_DNS\" ] && [ \"\$PREV_DNS\" != \"null\" ] && [ \"\$PREV_DNS\" != \"off\" ]; then " +
            "echo \"\$PREV_DNS\" > ${CoreConstants.ROOT_RUN_DIR}/.prev_dns; " +
            "settings put global private_dns_mode off; fi"
        ).exec()

        // 3. Launch the daemon via the command builder
        val res = Shell.cmd(*buildStartDaemonCommands(configPath).toTypedArray()).exec()

        if (!res.isSuccess) {
            throw RuntimeException("Failed to start Mihomo daemon: ${res.err.joinToString("\n")}")
        }

        val pid = res.out.lastOrNull()?.trim()?.toIntOrNull()
            ?: throw RuntimeException("Could not retrieve daemon PID")

        pid
    }

    /**
     * Restores Android Private DNS left over from a crashed previous root session.
     *
     * [startDaemon] saves the current private_dns_mode into .prev_dns and switches
     * it off; [stopDaemon] restores it and removes the marker. If the app process
     * or the daemon crashed, stopDaemon() never ran and Private DNS stays off.
     * On the next root start, an existing .prev_dns while no daemon is running is
     * therefore a crash signal: restore the saved mode and delete the marker.
     * The subsequent startDaemon() re-saves the restored value and disables
     * Private DNS again for the fresh session.
     *
     * Called from RootTProxyService.startProxy() before the daemon is started.
     */
    fun maybeRestorePrivateDns() {
        if (isDaemonRunning()) return
        runCatching {
            Shell.cmd(
                "if [ -f ${CoreConstants.ROOT_RUN_DIR}/.prev_dns ]; then " +
                        "RESTORE_DNS=\$(cat ${CoreConstants.ROOT_RUN_DIR}/.prev_dns 2>/dev/null); " +
                        "if [ -n \"\$RESTORE_DNS\" ]; then settings put global private_dns_mode \"\$RESTORE_DNS\"; fi; " +
                        "rm -f ${CoreConstants.ROOT_RUN_DIR}/.prev_dns; fi"
            ).exec()
        }
    }

    /**
     * Crash-recovery path for the OEM DNS reject chains saved by
     * [buildSaveOplusCommands]: if the app process died before [stopDaemon] ran,
     * the chains stay flushed while the save files survive, and a later save
     * would overwrite them with the already-emptied chains — the OEM rules
     * would be lost forever. While no daemon is running, existing save files
     * are therefore a crash signal: replay the saved rules and delete the
     * files. The subsequent startDaemon() re-saves the restored chains and
     * flushes them again for the fresh session (startDaemon also implicitly
     * recovers via its leading stopDaemon(), so this is the explicit safe
     * entry point for callers mirroring [maybeRestorePrivateDns]).
     */
    fun maybeRestoreOplusChains() {
        if (isDaemonRunning()) return
        runCatching { restoreOplusChains() }
    }

    fun stopDaemon(): Result<Unit> = runCatching {
        Shell.cmd(*buildStopDaemonCommands().toTypedArray()).exec()
    }

    /**
     * Pure builder for the daemon launch commands: prepare the run dir, spawn the
     * mihomo binary with gid 3005 (net_admin) for Android network permissions and
     * capture its PID. The PID is read back by the last command so [startDaemon]
     * can return it.
     */
    fun buildStartDaemonCommands(configPath: String): List<String> = listOf(
        "mkdir -p ${CoreConstants.ROOT_RUN_DIR}",
        // Find busybox to run daemon with gid 3005 (net_admin) for Android network permissions
        "BUSYBOX=\$(which busybox 2>/dev/null || ls /data/adb/*/bin/busybox 2>/dev/null | head -n 1); " +
                "if [ -n \"\$BUSYBOX\" ] && [ -x \"\$BUSYBOX\" ]; then EXEC_PREFIX=\"\$BUSYBOX setuidgid 0:3005\"; else EXEC_PREFIX=\"\"; fi; " +
                "nohup \$EXEC_PREFIX ${CoreConstants.ROOT_BIN_PATH} -d ${CoreConstants.ROOT_RUN_DIR} -f \"$configPath\" > ${CoreConstants.ROOT_LOG_FILE} 2>&1 & echo \$! > ${CoreConstants.ROOT_PID_FILE}",
        "cat ${CoreConstants.ROOT_PID_FILE}"
    )

    /**
     * Pure builder for the daemon stop commands: SIGTERM then SIGKILL via the pid
     * file, targeted killall of leftover core processes (never a broad `pkill -9 -f`,
     * which would kill unrelated processes matching the pattern), restore of
     * Android Private DNS saved by [startDaemon], and restore of the OEM DNS
     * reject chains snapshotted by [buildSaveOplusCommands] (appended after the
     * Private DNS block so a crash mid-stop still leaves the DNS marker last).
     */
    fun buildStopDaemonCommands(): List<String> = listOf(
        "if [ -f ${CoreConstants.ROOT_PID_FILE} ]; then " +
                "PID=\$(cat ${CoreConstants.ROOT_PID_FILE}); " +
                "kill -15 \$PID 2>/dev/null; " +
                "sleep 0.2; " +
                "kill -9 \$PID 2>/dev/null; " +
                "rm -f ${CoreConstants.ROOT_PID_FILE}; " +
                "fi; ",
        "killall mihomo 2>/dev/null || true; ",
        "killall libmihomo.so 2>/dev/null || true; ",
        "if [ -f ${CoreConstants.ROOT_RUN_DIR}/.prev_dns ]; then " +
                "RESTORE_DNS=\$(cat ${CoreConstants.ROOT_RUN_DIR}/.prev_dns 2>/dev/null); " +
                "if [ -n \"\$RESTORE_DNS\" ]; then settings put global private_dns_mode \"\$RESTORE_DNS\"; fi; " +
                "rm -f ${CoreConstants.ROOT_RUN_DIR}/.prev_dns; fi"
    ) + buildRestoreOplusCommands()

    /**
     * Pure builder for the OEM (OnePlus / Realme / ColorOS) chain flush step of
     * [startDaemon]: snapshot the current `fw_OUTPUT_oplus_dns` rules of both
     * address families into [CoreConstants.ROOT_OPLUS_RULES_V4_FILE] /
     * [CoreConstants.ROOT_OPLUS_RULES_V6_FILE] — only when the chain actually
     * exists, otherwise stale save files are removed — and only then flush the
     * chains for the daemon lifetime. The save MUST precede the flush, or the
     * snapshot would capture the already-emptied chains.
     */
    fun buildOplusFlushCommands(): List<String> =
        buildSaveOplusCommands() + listOf(
            "iptables -t filter -F ${CoreConstants.OPLUS_DNS_CHAIN} 2>/dev/null || true",
            "ip6tables -t filter -F ${CoreConstants.OPLUS_DNS_CHAIN} 2>/dev/null || true"
        )

    /**
     * Pure builder for the OEM chain snapshot: per address family, gate on the
     * chain's existence via `-nL` (exit code — never "grep with empty output as
     * success"), dump the rules with `-S` filtered to `-A` rule lines into the
     * family's save file, or remove a stale save file when the chain is gone.
     */
    fun buildSaveOplusCommands(): List<String> = listOf(
        "mkdir -p ${CoreConstants.ROOT_RUN_DIR}",
        saveOplusChainCommand("iptables", CoreConstants.ROOT_OPLUS_RULES_V4_FILE),
        saveOplusChainCommand("ip6tables", CoreConstants.ROOT_OPLUS_RULES_V6_FILE)
    )

    /**
     * Pure builder for the OEM chain restore: per address family, when the save
     * file exists, recreate the chain if it disappeared (e.g. the OEM firewall
     * was reset), replay every saved `-A` rule line through the family's binary
     * in the saved order, then delete the save file. Best-effort like every
     * teardown step (`2>/dev/null || true`): a failed replay never blocks stop.
     */
    fun buildRestoreOplusCommands(): List<String> = listOf(
        restoreOplusChainCommand("iptables", CoreConstants.ROOT_OPLUS_RULES_V4_FILE),
        restoreOplusChainCommand("ip6tables", CoreConstants.ROOT_OPLUS_RULES_V6_FILE)
    )

    private fun saveOplusChainCommand(tool: String, rulesFile: String): String =
        "if $tool -t filter -nL ${CoreConstants.OPLUS_DNS_CHAIN} >/dev/null 2>&1; then " +
                "$tool -t filter -S ${CoreConstants.OPLUS_DNS_CHAIN} 2>/dev/null | grep '^-A ' > $rulesFile; " +
                "else rm -f $rulesFile; fi"

    private fun restoreOplusChainCommand(tool: String, rulesFile: String): String =
        "if [ -f $rulesFile ]; then " +
                "if ! $tool -t filter -nL ${CoreConstants.OPLUS_DNS_CHAIN} >/dev/null 2>&1; then " +
                "$tool -t filter -N ${CoreConstants.OPLUS_DNS_CHAIN} 2>/dev/null || true; fi; " +
                "grep '^-A ' $rulesFile 2>/dev/null | while IFS= read -r line; do " +
                "$tool -t filter \$line 2>/dev/null || true; done; " +
                "rm -f $rulesFile; fi"

    /** Executes the OEM chain restore on the root shell (crash recovery path). */
    private fun restoreOplusChains() {
        Shell.cmd(*buildRestoreOplusCommands().toTypedArray()).exec()
    }
}
