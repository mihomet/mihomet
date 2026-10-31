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
import org.mihomet.app.core.update.CoreUpdateVerifier
import org.mihomet.app.data.api.MihomoApiClient
import org.mihomet.app.data.preferences.AppSettings
import java.io.File

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

    /**
     * Latest core release. ONLY our own patched-core repository is queried
     * (audit P0 §1.2): the bundled core is mihomo v1.19.33 patched for non-root
     * TUN and built by mihomet/core — stock MetaCubeX builds lack the patch and
     * are not validated against this app, so they are never offered or installed.
     */
    suspend fun checkLatestRelease(): Result<GitHubRelease> = withContext(Dispatchers.IO) {
        runCatching {
            httpClient.get(CoreConstants.CORE_RELEASES_API_LATEST) {
                header("User-Agent", "Mihomet-Android/1.0")
                header("Accept", "application/vnd.github.v3+json")
            }.body()
        }
    }

    private suspend fun downloadBytes(url: String): ByteArray {
        check(CoreUpdateVerifier.isTrustedCoreUrl(url)) {
            "Core update refused: untrusted download URL $url"
        }
        return httpClient.get(url) {
            header("User-Agent", "Mihomet-Android/1.0")
        }.body()
    }

    /**
     * Downloads, verifies and atomically installs the latest core release.
     *
     * Fail-closed verification chain (audit P0 §1.2), ALL steps must pass:
     *  1. manifest.json + manifest.json.sig must exist in the release;
     *  2. the manifest signature must verify against the embedded RSA-4096 public key;
     *  3. the manifest version must match the release tag; the device ABI must be listed;
     *  4. the downloaded .so must match the manifest sha256 AND exact byte size;
     *  5. the NEW binary must execute under the root shell (`mihomo -v` from
     *     run/mihomo.new) BEFORE the installed binary is touched;
     *  6. install is an atomic rename with the previous binary kept as
     *     bin/mihomo.bak and restored on any failure.
     */
    suspend fun downloadAndUpdateCore(release: GitHubRelease, onProgress: (Float) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val tag = release.tag_name.trim()
            val abi = CoreUpdateVerifier.deviceArtifactKey(Build.SUPPORTED_ABIS.firstOrNull())
                ?: throw IllegalStateException("Unsupported device ABI: ${Build.SUPPORTED_ABIS.firstOrNull()}")

            // --- 1. manifest + signature assets must exist ---
            val manifestAsset = release.assets.find { it.name == CoreConstants.CORE_MANIFEST_ASSET }
                ?: throw IllegalStateException(
                    "Core update refused: ${CoreConstants.CORE_MANIFEST_ASSET} is missing from release $tag"
                )
            val signatureAsset = release.assets.find { it.name == CoreConstants.CORE_MANIFEST_SIGNATURE_ASSET }
                ?: throw IllegalStateException(
                    "Core update refused: ${CoreConstants.CORE_MANIFEST_SIGNATURE_ASSET} is missing from release $tag"
                )

            onProgress(0.1f)
            val manifestBytes = downloadBytes(manifestAsset.browser_download_url)
            val signatureBytes = downloadBytes(signatureAsset.browser_download_url)

            // --- 2. signature over the exact manifest bytes (embedded public key) ---
            CoreUpdateVerifier.verifySignature(
                manifestBytes, signatureBytes, CoreConstants.CORE_UPDATE_PUBLIC_KEY_B64
            ).getOrElse {
                android.util.Log.w("CoreManager", "Core update refused: signature verification failed for $tag")
                throw IllegalStateException("Core update refused: manifest signature verification failed")
            }

            // --- 3. manifest sanity: schema, tag match, ABI artifact ---
            val manifest = CoreUpdateVerifier.parseManifest(manifestBytes).getOrElse {
                throw IllegalStateException("Core update refused: invalid ${CoreConstants.CORE_MANIFEST_ASSET} (${it.message})")
            }
            CoreUpdateVerifier.checkManifestVersion(manifest, tag).getOrElse {
                throw IllegalStateException("Core update refused: ${it.message}")
            }
            val artifact = CoreUpdateVerifier.selectArtifact(manifest, abi).getOrElse {
                throw IllegalStateException("Core update refused: ${it.message}")
            }

            val binaryAsset = release.assets.find { it.name == artifact.file }
                ?: throw IllegalStateException(
                    "Core update refused: ${artifact.file} is missing from release $tag"
                )

            onProgress(0.3f)
            val binaryBytes = downloadBytes(binaryAsset.browser_download_url)
            onProgress(0.6f)

            // --- 4. integrity: exact size + sha256 from the signed manifest ---
            CoreUpdateVerifier.verifyBinary(manifest, abi, binaryBytes).getOrElse {
                android.util.Log.w("CoreManager", "Core update refused: integrity check failed for $tag")
                throw IllegalStateException("Core update refused: downloaded core failed integrity verification")
            }

            // Stage the verified binary in the app cache (never executed from here —
            // app data is noexec; the root shell runs it from run/mihomo.new below).
            val tempBinFile = File(context.cacheDir, "mihomo_update.so")
            try {
                tempBinFile.writeBytes(binaryBytes)

                val upload = Shell.cmd(
                    "mkdir -p ${CoreConstants.ROOT_BIN_DIR} ${CoreConstants.ROOT_RUN_DIR}",
                    "rm -f ${CoreConstants.ROOT_RUN_NEW_BIN}",
                    "cp \"${tempBinFile.absolutePath}\" ${CoreConstants.ROOT_RUN_NEW_BIN}",
                    "chmod 755 ${CoreConstants.ROOT_RUN_NEW_BIN}"
                ).exec()
                if (!upload.isSuccess) {
                    throw RuntimeException(
                        "Core update failed: could not stage the new binary (${upload.err.joinToString("\n")})"
                    )
                }

                // --- 5. validate the NEW binary BEFORE touching the installed one ---
                val probe = Shell.cmd("\"${CoreConstants.ROOT_RUN_NEW_BIN}\" -v").exec()
                val probeOutput = (probe.out + probe.err).joinToString("\n")
                val expectedVersionToken = manifest.version.removePrefix("core-")
                if (!probe.isSuccess || !probeOutput.contains(expectedVersionToken)) {
                    throw IllegalStateException(
                        "Core update refused: new binary failed 'mihomo -v' validation for $expectedVersionToken"
                    )
                }

                // --- 6. atomic replace with backup + rollback on failure ---
                installVerifiedBinary()

                // Record the installed core version so bootstrapRootBinary does not
                // roll the update back on the next start (sanitized for the shell).
                val installedVersion = tag.filter {
                    it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == '+'
                }
                if (installedVersion.isNotEmpty()) {
                    Shell.cmd(
                        "mkdir -p ${CoreConstants.ROOT_RUN_DIR}",
                        "echo '$installedVersion' > ${CoreConstants.ROOT_CORE_VERSION_FILE}"
                    ).exec()
                }

                onProgress(1.0f)
                // If root daemon is currently running, restart it with updated binary
                if (org.mihomet.app.core.root.RootShellManager.isDaemonRunning()) {
                    org.mihomet.app.core.root.RootTProxyService.restart(context)
                }
            } finally {
                tempBinFile.delete()
                // The staged copy was consumed by the atomic rename; remove any
                // leftover from a failed attempt so run/ never keeps stale binaries.
                Shell.cmd("rm -f ${CoreConstants.ROOT_RUN_NEW_BIN}").exec()
            }
        }
    }

    /**
     * Atomic replace of [CoreConstants.ROOT_BIN_PATH] with the already-validated
     * [CoreConstants.ROOT_RUN_NEW_BIN]: the previous binary is backed up first,
     * the new one moves into place via rename(2) (same filesystem → atomic, never
     * a truncated file), and any failure restores the backup.
     */
    private fun installVerifiedBinary() {
        fun shellOk(vararg cmds: String): Boolean =
            Shell.cmd(*cmds).exec().isSuccess

        val backupCmds = arrayOf(
            "if [ -s ${CoreConstants.ROOT_BIN_PATH} ]; then cp -f ${CoreConstants.ROOT_BIN_PATH} ${CoreConstants.ROOT_BIN_BACKUP_PATH}; fi"
        )
        if (!shellOk(*backupCmds)) {
            throw RuntimeException("Core update failed: could not back up the previous binary")
        }

        val installCmds = arrayOf(
            "mv -f ${CoreConstants.ROOT_RUN_NEW_BIN} ${CoreConstants.ROOT_BIN_PATH}",
            "chmod 755 ${CoreConstants.ROOT_BIN_PATH}",
            "chcon u:object_r:system_file:s0 ${CoreConstants.ROOT_BIN_PATH} 2>/dev/null || true"
        )
        if (!shellOk(*installCmds)) {
            android.util.Log.w("CoreManager", "Install failed — restoring the previous binary")
            val restored = shellOk(
                "if [ -s ${CoreConstants.ROOT_BIN_BACKUP_PATH} ]; then cp -f ${CoreConstants.ROOT_BIN_BACKUP_PATH} ${CoreConstants.ROOT_BIN_PATH}; fi",
                "chmod 755 ${CoreConstants.ROOT_BIN_PATH}"
            )
            if (!restored) {
                android.util.Log.e("CoreManager", "Rollback failed — restore manually from ${CoreConstants.ROOT_BIN_BACKUP_PATH}")
            }
            throw RuntimeException("Core update failed: install step failed, previous binary restored")
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

            // Audit P1 §2.2: per-run random secret + ephemeral free port. The probe
            // core is a fully functional mihomo instance — a static secret baked into
            // the APK (or a fixed port) would let any local app reach its controller
            // API and would race with other listeners.
            val probePort = CoreConstants.findFreePort()
            val probeSecret = CoreConstants.generateRandomHexSecret()
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
                // Release the probe client's Ktor engine (short-lived client, single probe run).
                probeApiClient.close()
                runCatching { probeDir.deleteRecursively() }
            }
        }
    }
}
