package org.mihomet.app.core

import android.content.Context
import android.os.Build
import com.topjohnwu.superuser.Shell
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

    companion object {
        const val ROOT_BASE_DIR = "/data/adb/mihomet"
        const val ROOT_BIN_DIR = "$ROOT_BASE_DIR/bin"
        const val ROOT_BIN_PATH = "$ROOT_BIN_DIR/mihomo"
        const val ROOT_RUN_DIR = "$ROOT_BASE_DIR/run"
        const val ROOT_PID_FILE = "$ROOT_BASE_DIR/mihomo.pid"
        const val ROOT_LOG_FILE = "$ROOT_RUN_DIR/mihomo.log"
        const val ROOT_CONFIG_PATH = "$ROOT_RUN_DIR/config.yaml"
    }

    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    fun getBundledLibraryPath(): String {
        return context.applicationInfo.nativeLibraryDir + "/libmihomo.so"
    }

    suspend fun bootstrapRootBinary(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val bundledPath = getBundledLibraryPath()
            val checkResult = Shell.cmd("[ -f $ROOT_BIN_PATH ] && echo EXISTS || echo MISSING").exec()
            val exists = checkResult.out.any { it.contains("EXISTS") }

            if (!exists) {
                val setupCmds = listOf(
                    "mkdir -p $ROOT_BIN_DIR",
                    "mkdir -p $ROOT_RUN_DIR",
                    "cp \"$bundledPath\" $ROOT_BIN_PATH",
                    "chmod 755 $ROOT_BIN_PATH",
                    "chcon u:object_r:system_file:s0 $ROOT_BIN_PATH 2>/dev/null || true"
                )
                val res = Shell.cmd(*setupCmds.toTypedArray()).exec()
                if (!res.isSuccess) {
                    throw RuntimeException("Failed to bootstrap root binary: ${res.err.joinToString("\n")}")
                }
            }
            ROOT_BIN_PATH
        }
    }

    fun getCoreBinaryPath(isRootMode: Boolean): String {
        return if (isRootMode) {
            ROOT_BIN_PATH
        } else {
            getBundledLibraryPath()
        }
    }

    suspend fun checkLatestRelease(): Result<GitHubRelease> = withContext(Dispatchers.IO) {
        runCatching {
            httpClient.get("https://api.github.com/repos/MetaCubeX/mihomo/releases/latest").body()
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
            val responseBytes: ByteArray = httpClient.get(asset.browser_download_url).body()
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
                "mkdir -p $ROOT_BIN_DIR",
                "cp \"${tempBinFile.absolutePath}\" $ROOT_BIN_PATH",
                "chmod 755 $ROOT_BIN_PATH",
                "chcon u:object_r:system_file:s0 $ROOT_BIN_PATH 2>/dev/null || true"
            )
            val result = Shell.cmd(*cmds.toTypedArray()).exec()

            tempGzFile.delete()
            tempBinFile.delete()

            if (!result.isSuccess) {
                throw RuntimeException("Root install failed: ${result.err.joinToString("\n")}")
            }
        }
    }
}
