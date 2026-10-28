package org.mihomet.app.core

import android.content.Context
import android.os.Build
import com.topjohnwu.superuser.Shell
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.mihomet.app.core.config.ConfigGenerator
import org.mihomet.app.data.api.MihomoApiClient
import org.mihomet.app.data.preferences.AppSettings
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

@Serializable
data class GitHubRelease(
    val tag_name: String = "",
    val name: String = "",
    val assets: List<GitHubAsset> = emptyList()
)

@Serializable
data class GitHubAsset(
    val name: String = "",
    val browser_download_url: String = "",
    val size: Long = 0L
)

class CoreManager(private val context: Context) {

    private val httpClient = HttpClient {
        followRedirects = true
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    fun getBundledLibraryPath(): String {
        return context.applicationInfo.nativeLibraryDir + "/libmihomo.so"
    }

    fun ensureGeoDatabases(targetDir: File) {
        targetDir.mkdirs()
        listOf("geoip.metadb", "geosite.dat").forEach { filename ->
            val destFile = File(targetDir, filename)
            if (!destFile.exists() || destFile.length() == 0L) {
                runCatching {
                    context.assets.open(filename).use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    suspend fun bootstrapRootBinary(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val bundledPath = getBundledLibraryPath()
            val checkResult = Shell.cmd(
                "if [ ! -f ${CoreConstants.ROOT_BIN_PATH} ] || [ \"\$(stat -c %s ${CoreConstants.ROOT_BIN_PATH} 2>/dev/null)\" != \"\$(stat -c %s \"$bundledPath\" 2>/dev/null)\" ]; then echo UPDATE_NEEDED; else echo UP_TO_DATE; fi"
            ).exec()
            val updateNeeded = checkResult.out.any { it.contains("UPDATE_NEEDED") }

            if (updateNeeded) {
                val setupCmds = listOf(
                    "mkdir -p ${CoreConstants.ROOT_BIN_DIR}",
                    "mkdir -p ${CoreConstants.ROOT_RUN_DIR}",
                    "cp \"$bundledPath\" ${CoreConstants.ROOT_BIN_PATH}",
                    "chmod 755 ${CoreConstants.ROOT_BIN_PATH}",
                    "chcon u:object_r:system_file:s0 ${CoreConstants.ROOT_BIN_PATH} 2>/dev/null || true"
                )
                val res = Shell.cmd(*setupCmds.toTypedArray()).exec()
                if (!res.isSuccess) {
                    throw RuntimeException("Failed to bootstrap root binary: ${res.err.joinToString("\n")}")
                }
            }

            // Also ensure geo databases are deployed for root mode
            ensureGeoDatabases(context.filesDir)
            val appGeoip = File(context.filesDir, "geoip.metadb")
            val appGeosite = File(context.filesDir, "geosite.dat")
            if (appGeoip.exists() && appGeosite.exists()) {
                Shell.cmd(
                    "mkdir -p ${CoreConstants.ROOT_RUN_DIR}",
                    "if [ ! -f ${CoreConstants.ROOT_RUN_DIR}/geoip.metadb ] || [ ! -s ${CoreConstants.ROOT_RUN_DIR}/geoip.metadb ]; then cp \"${appGeoip.absolutePath}\" ${CoreConstants.ROOT_RUN_DIR}/geoip.metadb; fi",
                    "if [ ! -f ${CoreConstants.ROOT_RUN_DIR}/geosite.dat ] || [ ! -s ${CoreConstants.ROOT_RUN_DIR}/geosite.dat ]; then cp \"${appGeosite.absolutePath}\" ${CoreConstants.ROOT_RUN_DIR}/geosite.dat; fi",
                    "chmod 644 ${CoreConstants.ROOT_RUN_DIR}/geoip.metadb ${CoreConstants.ROOT_RUN_DIR}/geosite.dat 2>/dev/null || true"
                ).exec()
            }

            CoreConstants.ROOT_BIN_PATH
        }
    }

    fun getCoreBinaryPath(isRootMode: Boolean): String {
        return if (isRootMode) {
            CoreConstants.ROOT_BIN_PATH
        } else {
            getBundledLibraryPath()
        }
    }

    suspend fun checkLatestRelease(): Result<GitHubRelease> = withContext(Dispatchers.IO) {
        runCatching {
            httpClient.get("https://api.github.com/repos/MetaCubeX/mihomo/releases/latest") {
                header("User-Agent", "Mihomet-Android/1.0")
                header("Accept", "application/vnd.github.v3+json")
            }.body()
        }
    }

    suspend fun downloadAndUpdateCore(release: GitHubRelease, onProgress: (Float) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val targetArchKeyword = when {
                primaryAbi.contains("arm64") -> "arm64-v8"
                primaryAbi.contains("armeabi") || primaryAbi.contains("v7a") -> "armv7"
                primaryAbi.contains("x86_64") -> "amd64"
                else -> "arm64-v8"
            }

            val asset = release.assets.find {
                it.name.contains("android", ignoreCase = true) &&
                        it.name.contains(targetArchKeyword, ignoreCase = true) &&
                        it.name.endsWith(".gz", ignoreCase = true)
            } ?: throw IllegalStateException("No compatible asset found for ABI $primaryAbi")

            val tempGzFile = File(context.cacheDir, "mihomo_update.gz")
            val tempBinFile = File(context.cacheDir, "mihomo_update")

            // Download asset
            val responseBytes: ByteArray = httpClient.get(asset.browser_download_url) {
                header("User-Agent", "Mihomet-Android/1.0")
            }.body()
            tempGzFile.writeBytes(responseBytes)

            // Decompress gzip
            GZIPInputStream(tempGzFile.inputStream()).use { input ->
                FileOutputStream(tempBinFile).use { output ->
                    input.copyTo(output)
                }
            }
            tempBinFile.setExecutable(true)

            // Install into /data/adb/mihomet/bin/mihomo via root shell
            val cmds = listOf(
                "mkdir -p ${CoreConstants.ROOT_BIN_DIR}",
                "cp \"${tempBinFile.absolutePath}\" ${CoreConstants.ROOT_BIN_PATH}",
                "chmod 755 ${CoreConstants.ROOT_BIN_PATH}",
                "chcon u:object_r:system_file:s0 ${CoreConstants.ROOT_BIN_PATH} 2>/dev/null || true"
            )
            val result = Shell.cmd(*cmds.toTypedArray()).exec()

            tempGzFile.delete()
            tempBinFile.delete()

            if (!result.isSuccess) {
                throw RuntimeException("Root install failed: ${result.err.joinToString("\n")}")
            }

            // If root daemon is currently running, restart it with updated binary
            if (org.mihomet.app.core.root.RootShellManager.isDaemonRunning()) {
                org.mihomet.app.core.root.RootTProxyService.restart(context)
            }
        }
    }

    suspend fun <T> runTemporaryCoreProbe(
        rawYaml: String,
        block: suspend (apiClient: MihomoApiClient) -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val binaryPath = getBundledLibraryPath()
            val probeDir = File(context.cacheDir, "core_probe").apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }

            val probePort = CoreConstants.PROBE_PORT
            val probeSecret = CoreConstants.PROBE_SECRET
            val probeConfig = ConfigGenerator.generateRuntimeConfig(
                profileYamlContent = rawYaml,
                settings = AppSettings(
                    mixedPort = 17895,
                    dnsPort = 11053,
                    tproxyPort = 17893,
                    controllerPort = probePort,
                    controllerSecret = probeSecret
                ),
                tunFd = null
            )

            val testConfigFile = File(probeDir, "config.yaml").apply { writeText(probeConfig) }
            val testLogFile = File(probeDir, "probe.log")

            val pb = ProcessBuilder(
                binaryPath,
                "-d", probeDir.absolutePath,
                "-f", testConfigFile.absolutePath
            ).redirectErrorStream(true)
             .redirectOutput(testLogFile)

            pb.environment()["HOME"] = probeDir.absolutePath
            pb.environment()["TMPDIR"] = context.cacheDir.absolutePath

            val process = pb.start()
            val probeApiClient = MihomoApiClient(
                host = "127.0.0.1",
                port = probePort,
                secret = probeSecret
            )

            try {
                var ready = false
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < 3000) {
                    if (probeApiClient.getVersion().isSuccess) {
                        ready = true
                        break
                    }
                    delay(80)
                }
                if (!ready) {
                    throw IllegalStateException("Core probe failed to initialize in time")
                }

                block(probeApiClient)
            } finally {
                process.destroy()
                runCatching { probeDir.deleteRecursively() }
            }
        }
    }
}
