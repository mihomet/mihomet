package org.mihomet.app.core.root

import com.topjohnwu.superuser.Shell
import org.mihomet.app.core.CoreManager

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
                "if [ -f ${CoreManager.ROOT_PID_FILE} ]; then " +
                        "PID=\$(cat ${CoreManager.ROOT_PID_FILE}); " +
                        "if kill -0 \$PID 2>/dev/null; then echo ALIVE; else echo DEAD; fi; " +
                        "else echo DEAD; fi"
            ).exec()
            res.out.any { it.trim() == "ALIVE" }
        }.getOrDefault(false)
    }

    fun startDaemon(configPath: String): Result<Int> = runCatching {
        // Stop any running instance first
        stopDaemon()

        // 1. OnePlus / Realme / ColorOS Android 16 fix: flush custom OEM DNS reject chains
        Shell.cmd(
            "iptables -t filter -F fw_OUTPUT_oplus_dns 2>/dev/null || true",
            "ip6tables -t filter -F fw_OUTPUT_oplus_dns 2>/dev/null || true"
        ).exec()

        // 2. Save and turn off Android Private DNS (prevents DoT port 853 validation failures)
        Shell.cmd(
            "PREV_DNS=\$(settings get global private_dns_mode 2>/dev/null); " +
            "if [ -n \"\$PREV_DNS\" ] && [ \"\$PREV_DNS\" != \"null\" ] && [ \"\$PREV_DNS\" != \"off\" ]; then " +
            "echo \"\$PREV_DNS\" > ${CoreManager.ROOT_RUN_DIR}/.prev_dns; " +
            "settings put global private_dns_mode off; fi"
        ).exec()

        // 3. Find busybox to run daemon with gid 3005 (net_admin) for Android network permissions
        val startCmd = "BUSYBOX=\$(which busybox 2>/dev/null || ls /data/adb/*/bin/busybox 2>/dev/null | head -n 1); " +
                "if [ -n \"\$BUSYBOX\" ] && [ -x \"\$BUSYBOX\" ]; then EXEC_PREFIX=\"\$BUSYBOX setuidgid 0:3005\"; else EXEC_PREFIX=\"\"; fi; " +
                "nohup \$EXEC_PREFIX ${CoreManager.ROOT_BIN_PATH} -d ${CoreManager.ROOT_RUN_DIR} -f \"$configPath\" > ${CoreManager.ROOT_LOG_FILE} 2>&1 & echo \$! > ${CoreManager.ROOT_PID_FILE}"

        val res = Shell.cmd(
            "mkdir -p ${CoreManager.ROOT_RUN_DIR}",
            startCmd,
            "cat ${CoreManager.ROOT_PID_FILE}"
        ).exec()

        if (!res.isSuccess) {
            throw RuntimeException("Failed to start Mihomo daemon: ${res.err.joinToString("\n")}")
        }

        val pid = res.out.lastOrNull()?.trim()?.toIntOrNull()
            ?: throw RuntimeException("Could not retrieve daemon PID")

        pid
    }

    fun stopDaemon(): Result<Unit> = runCatching {
        Shell.cmd(
            "if [ -f ${CoreManager.ROOT_PID_FILE} ]; then " +
                    "PID=\$(cat ${CoreManager.ROOT_PID_FILE}); " +
                    "kill -15 \$PID 2>/dev/null; " +
                    "sleep 0.2; " +
                    "kill -9 \$PID 2>/dev/null; " +
                    "rm -f ${CoreManager.ROOT_PID_FILE}; " +
                    "fi; " +
                    "killall mihomo 2>/dev/null || true; " +
                    "if [ -f ${CoreManager.ROOT_RUN_DIR}/.prev_dns ]; then " +
                    "RESTORE_DNS=\$(cat ${CoreManager.ROOT_RUN_DIR}/.prev_dns 2>/dev/null); " +
                    "if [ -n \"\$RESTORE_DNS\" ]; then settings put global private_dns_mode \"\$RESTORE_DNS\"; fi; " +
                    "rm -f ${CoreManager.ROOT_RUN_DIR}/.prev_dns; fi"
        ).exec()
    }
}
