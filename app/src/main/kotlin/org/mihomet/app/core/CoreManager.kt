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

/** Matches a version token like "v1.19.33-mihomet.1" in the core's `-v` output. */
private val CORE_VERSION_REGEX = Regex("""v[0-9][A-Za-z0-9._+\-]*""")

/**
 * Pure decision helper for [CoreManager.bootstrapRootBinary]: whether the bundled
 * core binary should be copied over the already-installed root-mode binary.
 *
 * The caller only invokes this when an installed binary exists (size > 0), but the
 * `installedSize <= 0` guard keeps first-bootstrap semantics ("copy") even if the
 * contract is ever violated.
 *
 * Decision table:
 *  - installed metadata missing (`installedVersion == null`)  → legacy: copy when sizes differ
 *  - installed version == bundled version                     → same core: copy when sizes differ
 *  - installed version != bundled version (user-updated core) → keep (never roll back an update)
 *  - bundled version unknown (`bundledVersion == null`)       → legacy: copy when sizes differ
 */
internal fun shouldCopyBundledCore(
    installedVersion: String?,
    bundledVersion: String?,
    installedSize: Long,
    bundledSize: Long
): Boolean {
    // No usable installed binary (first bootstrap / corrupted install): copy.
    if (installedSize <= 0L) return true
    // Version metadata present on both sides and different: the installed core was
    // updated through the in-app updater — keep it instead of rolling back to the
    // (differently sized) bundled binary on every app restart.
    if (installedVersion != null && bundledVersion != null && installedVersion != bundledVersion) {
        return false
    }
    // Same version (or the version is unknown on one side): legacy size-based check.
    return installedSize != bundledSize
}

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

    /**
     * Version of the bundled core binary as reported by the binary itself
     * (mihomo `-v`, e.g. "v1.19.33-mihomet.1"), or null when it cannot be
     * determined. Used to compare the installed root core against the bundled one.
     */
    private fun getBundledCoreVersion(): String? = runCatching {
        val process = ProcessBuilder(getBundledLibraryPath(), "-v")
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        CORE_VERSION_REGEX.find(output)?.value
    }.getOrNull()

    /**
     * Version of the core currently installed at [CoreConstants.ROOT_BIN_PATH],
     * as recorded by [downloadAndUpdateCore] in [CoreConstants.ROOT_CORE_VERSION_FILE].
     * Null when no metadata exists (the core was never updated through the app).
     */
    private fun readInstalledCoreVersion(): String? {
        return runCatching {
            val res = Shell.cmd("cat ${CoreConstants.ROOT_CORE_VERSION_FILE} 2>/dev/null").exec()
            res.out.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    suspend fun bootstrapRootBinary(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val bundledPath = getBundledLibraryPath()
            val installedSize = Shell.cmd("stat -c %s ${CoreConstants.ROOT_BIN_PATH} 2>/dev/null").exec()
                .out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
            val bundledSize = Shell.cmd("stat -c %s \"$bundledPath\" 2>/dev/null").exec()
                .out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L

            val copyNeeded = if (installedSize <= 0L) {
                // First bootstrap (or a broken install): the bundled binary is the
                // only guaranteed-working core.
                true
            } else {
                // Version-aware check (audit P2 §8): an in-app-updated core must
                // survive app restarts instead of being rolled back by the old
                // size-only comparison.
                val installedVersion = readInstalledCoreVersion()
                // Only pay the cost of querying the bundled version when version
                // metadata exists; without metadata the decision is size-based.
                val bundledVersion = if (installedVersion != null) getBundledCoreVersion() else null
                val copy = shouldCopyBundledCore(installedVersion, bundledVersion, installedSize, bundledSize)
                if (!copy) {
                    android.util.Log.i(
                        "CoreManager",
                        "Keeping updated core at ${CoreConstants.ROOT_BIN_PATH} " +
                                "(installed=$installedVersion, bundled=${bundledVersion ?: "unknown"})"
                    )
                }
                copy
            }

            if (copyNeeded) {
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

            // Record the installed core version (audit P2 §8) so bootstrapRootBinary
            // can tell a user-updated core apart from a stale copy of the bundled one
            // and does not roll the update back on the next start.
            // Sanitize the tag before interpolating it into a root shell command.
            val installedVersion = release.tag_name.trim().filter {
                it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == '+'
            }
            if (installedVersion.isNotEmpty()) {
                Shell.cmd(
                    "mkdir -p ${CoreConstants.ROOT_RUN_DIR}",
                    "echo '$installedVersion' > ${CoreConstants.ROOT_CORE_VERSION_FILE}"
                ).exec()
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
