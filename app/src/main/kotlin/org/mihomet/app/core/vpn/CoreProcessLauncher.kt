package org.mihomet.app.core.vpn

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import org.mihomet.app.core.CoreConstants
import org.mihomet.app.core.CoreManager
import java.io.File

/**
 * Spawns the bundled mihomo core as a child process: prepares the run
 * directory (geo databases, runtime config file, log file), builds the
 * `ProcessBuilder` command line and hands the TUN fd over as fd 3
 * (stdin dup2 + `CoreConstants.TUN_FD_SHELL_REDIRECT`, FD_CLOEXEC cleared).
 *
 * Extracted verbatim from [MihometVpnService] — pure move, no behavior change.
 */
class CoreProcessLauncher(
    private val context: android.content.Context,
    private val coreManager: CoreManager
) {

    /** Everything the caller must keep a handle on after a successful spawn. */
    data class Launch(
        val process: Process,
        val workDir: File,
        val configFile: File,
        val logFile: File
    )

    /**
     * Writes [runtimeYaml] into [workDir], spawns the core and returns the
     * live process with its file handles. [tunFd] is the established TUN
     * interface whose descriptor the core attaches as fd 3.
     */
    fun launch(runtimeYaml: String, workDir: File, tunFd: ParcelFileDescriptor): Launch {
        val vpnWorkDir = workDir.apply { mkdirs() }
        coreManager.ensureGeoDatabases(vpnWorkDir)
        val vpnConfigFile = File(vpnWorkDir, "config.yaml").apply {
            writeText(runtimeYaml)
        }
        val vpnLogFile = File(vpnWorkDir, "mihomo.log")

        // Clear FD_CLOEXEC so child process inherits the descriptor
        val fd = tunFd.fd
        val fdObj = tunFd.fileDescriptor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val currentFlags = Os.fcntlInt(fdObj, OsConstants.F_GETFD, 0)
            Os.fcntlInt(fdObj, OsConstants.F_SETFD, currentFlags and OsConstants.FD_CLOEXEC.inv())
        } else {
            runCatching {
                val fcntlIntMethod = Os::class.java.getMethod("fcntlInt", java.io.FileDescriptor::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                val currentFlags = fcntlIntMethod.invoke(null, fdObj, OsConstants.F_GETFD, 0) as Int
                fcntlIntMethod.invoke(null, fdObj, OsConstants.F_SETFD, currentFlags and OsConstants.FD_CLOEXEC.inv())
            }
        }

        val binaryPath = coreManager.getBundledLibraryPath()
        val pb = ProcessBuilder(
            "/system/bin/sh",
            "-c",
            "exec \"$binaryPath\" -d \"${vpnWorkDir.absolutePath}\" -f \"${vpnConfigFile.absolutePath}\" ${CoreConstants.TUN_FD_SHELL_REDIRECT}"
        ).redirectInput(ProcessBuilder.Redirect.INHERIT)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(vpnLogFile))

        pb.environment()["HOME"] = vpnWorkDir.absolutePath
        pb.environment()["TMPDIR"] = context.cacheDir.absolutePath

        // Pass fd 3 (duplicated from stdin via shell launcher)
        val savedStdin = runCatching { Os.dup(java.io.FileDescriptor.`in`) }.getOrNull()
        val proc = try {
            Os.dup2(fdObj, 0)
            pb.start()
        } finally {
            if (savedStdin != null) {
                runCatching {
                    Os.dup2(savedStdin, 0)
                    Os.close(savedStdin)
                }
            } else {
                runCatching {
                    val devNull = Os.open("/dev/null", OsConstants.O_RDONLY, 0)
                    Os.dup2(devNull, 0)
                    Os.close(devNull)
                }
            }
        }
        return Launch(proc, vpnWorkDir, vpnConfigFile, vpnLogFile)
    }
}
