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

        val startCmd = "nohup ${CoreManager.ROOT_BIN_PATH} -d ${CoreManager.ROOT_RUN_DIR} -f \"$configPath\" > ${CoreManager.ROOT_LOG_FILE} 2>&1 & echo \$! > ${CoreManager.ROOT_PID_FILE}"
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
                    "fi"
        ).exec()
    }
}
