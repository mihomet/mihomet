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
        //    DNS reject chains in the kernel's own iptables-save format (F09 —
        //    quoted arguments like `--comment "text with spaces"` survive any
        //    re-tokenization), then flush them for the daemon lifetime.
        //    Without the snapshot the OEM rules would be lost forever;
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
     * are therefore a crash signal: replay the saved rules (deleting the files
     * only on a successful replay) and keep them on failure. The subsequent
     * startDaemon() re-saves the restored chains and flushes them again for the
     * fresh session (startDaemon also implicitly recovers via its leading
     * stopDaemon(), so this is the explicit safe entry point for callers
     * mirroring [maybeRestorePrivateDns]).
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
     * Pure builder for the daemon stop commands: SIGTERM then SIGKILL via the
     * pid file, best-effort cleanup of leftover core processes by our FULL
     * binary path only, restore of Android Private DNS saved by [startDaemon],
     * and restore of the OEM DNS reject chains snapshotted by
     * [buildSaveOplusCommands] (appended after the Private DNS block so a crash
     * mid-stop still leaves the DNS marker last).
     */
    fun buildStopDaemonCommands(): List<String> = listOf(
        "if [ -f ${CoreConstants.ROOT_PID_FILE} ]; then " +
                "PID=\$(cat ${CoreConstants.ROOT_PID_FILE}); " +
                "kill -15 \$PID 2>/dev/null; " +
                "sleep 0.2; " +
                "kill -9 \$PID 2>/dev/null; " +
                "rm -f ${CoreConstants.ROOT_PID_FILE}; " +
                "fi; ",
        // F10: name-based `killall mihomo` / `killall libmihomo.so` murdered a
        // FOREIGN mihomo belonging to another app/user. `pkill -f` matches the
        // full command line, and the pattern is our exact binary path — a core
        // installed anywhere else never matches. The pid-file kill above stays
        // the primary mechanism; this is best-effort cleanup for crashed
        // sessions (a missing pkill binary degrades to a no-op via `|| true`).
        "pkill -f '${CoreConstants.ROOT_BIN_PATH}' 2>/dev/null || true; ",
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
     *
     * Documented trade-off (F09): if the snapshot fails while the chain exists
     * and still has rules (e.g. the save binary errored), the flush STILL runs —
     * the OEM chain keeps rejecting DNS queries, so skipping it would leave root
     * mode broken. The potential loss of the OEM rules is logged as a warning by
     * the save command itself; in the normal flow the risk is negligible because
     * startDaemon's leading stopDaemon() has already replayed (and consumed) any
     * previous snapshot before the fresh one is taken.
     */
    fun buildOplusFlushCommands(): List<String> =
        buildSaveOplusCommands() + listOf(
            "iptables -t filter -F ${CoreConstants.OPLUS_DNS_CHAIN} 2>/dev/null || true",
            "ip6tables -t filter -F ${CoreConstants.OPLUS_DNS_CHAIN} 2>/dev/null || true"
        )

    /**
     * Pure builder for the OEM chain snapshot (F09): per address family, gate on
     * the chain's existence via `-nL` (exit code — never "grep with empty output
     * as success"), then dump the chain with `iptables-save`/`ip6tables-save`
     * filtered to the chain's own `-A` rule lines into the family's save file.
     *
     * The iptables-save format carries the kernel's own quoting (e.g.
     * `--comment "text with spaces"`), so replaying the lines through
     * `iptables-restore` can never mis-tokenize a rule — unlike the historical
     * `iptables -S` + line-by-line shell replay, which broke on word-splitting.
     * Jump rules from OTHER chains (e.g. `-A OUTPUT -j fw_OUTPUT_oplus_dns`)
     * are deliberately filtered out: the restore appends (`--noflush`) and such
     * a replay would duplicate the jump on every session. An empty dump (chain
     * without rules) or a failed dump removes a stale save file — an empty
     * chain has nothing to restore; the failed-dump case is logged as a warning
     * (see [buildOplusFlushCommands] for the flush-anyway trade-off).
     */
    fun buildSaveOplusCommands(): List<String> = listOf(
        "mkdir -p ${CoreConstants.ROOT_RUN_DIR}",
        saveOplusChainCommand("iptables", "iptables-save", CoreConstants.ROOT_OPLUS_RULES_V4_FILE),
        saveOplusChainCommand("ip6tables", "ip6tables-save", CoreConstants.ROOT_OPLUS_RULES_V6_FILE)
    )

    /**
     * Pure builder for the OEM chain restore (F09): per address family, when the
     * save file exists, recreate the chain if it disappeared, then feed the
     * saved rule lines through `iptables-restore`/`ip6tables-restore --noflush`
     * as an `*filter` stream. The restore binary parses the kernel's own
     * quoting, so rules with spaces or quotes in `--comment` survive; `--noflush`
     * APPENDS to the existing table — our startup flushed the chain, so the
     * replay refills it without touching any other rule. The save file is
     * deleted ONLY after a successful restore; on failure it is KEPT for the
     * next attempt / manual recovery and the failure is logged on stderr.
     * Old-format snapshots (plain `-A` rule lines from the previous line-replay
     * implementation) are valid stream content and restore unchanged.
     */
    fun buildRestoreOplusCommands(): List<String> = listOf(
        restoreOplusChainCommand("iptables", CoreConstants.ROOT_OPLUS_RULES_V4_FILE),
        restoreOplusChainCommand("ip6tables", CoreConstants.ROOT_OPLUS_RULES_V6_FILE)
    )

    private fun saveOplusChainCommand(tool: String, saveTool: String, rulesFile: String): String =
        "if $tool -t filter -nL ${CoreConstants.OPLUS_DNS_CHAIN} >/dev/null 2>&1; then " +
                "if $saveTool -t filter 2>/dev/null | grep -E '^-A ${CoreConstants.OPLUS_DNS_CHAIN} ' > $rulesFile 2>/dev/null; then :; " +
                "else rm -f $rulesFile; echo 'mihomet: warning: OEM chain snapshot failed; rules will be lost' >&2; fi; " +
                "else rm -f $rulesFile; fi"

    private fun restoreOplusChainCommand(tool: String, rulesFile: String): String =
        "if [ -f $rulesFile ]; then " +
                "$tool -t filter -N ${CoreConstants.OPLUS_DNS_CHAIN} 2>/dev/null || true; " +
                "if { echo '*filter'; grep -E '^-A ${CoreConstants.OPLUS_DNS_CHAIN} ' $rulesFile 2>/dev/null; echo COMMIT; } | $tool-restore --noflush 2>/dev/null; then " +
                "rm -f $rulesFile; " +
                "else echo 'mihomet: OEM chain restore failed; snapshot kept in $rulesFile' >&2; fi; fi"

    /** Executes the OEM chain restore on the root shell (crash recovery path). */
    private fun restoreOplusChains() {
        Shell.cmd(*buildRestoreOplusCommands().toTypedArray()).exec()
    }
}
