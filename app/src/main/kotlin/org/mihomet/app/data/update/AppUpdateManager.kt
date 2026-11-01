package org.mihomet.app.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
// Ktor 3: readAvailable(ByteArray) moved from the ByteReadChannel interface to
// an extension in ByteReadChannelOperationsKt — explicit import required.
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.mihomet.app.core.GitHubRelease
import org.mihomet.app.core.update.CoreUpdateVerifier
import org.mihomet.app.data.update.AppUpdateVerifier.UpdateDecision
import java.io.File
import java.security.MessageDigest

/**
 * Orchestrates the in-app self-update flow (audit P1.4): GitHub release check,
 * fail-closed verification ([AppUpdateVerifier]), streamed APK download into the
 * app-external cache and a [PackageInstaller] session with the standard
 * user-consent flow. All decisions go through the pure
 * [AppUpdateStateMachine]; this class only performs side effects.
 *
 * Security posture:
 *  - HTTPS-only talks to api.github.com / github.com release URLs (pinned);
 *  - the downloaded APK must match the release asset's exact byte size AND be
 *    signed with the SAME certificate as the installed app — anything else is
 *    deleted and reported as an error, the manual release-page route is the
 *    only fallback (never a silent one);
 *  - the "install unknown apps" gate is never bypassed: when
 *    [android.content.pm.PackageManager.canRequestPackageInstalls] is false the
 *    user is sent to the system settings and must come back on their own;
 *  - the install-status callback only launches the platform's own
 *    confirm-install dialog (action + component guarded).
 */
class AppUpdateManager(private val context: Context) {

    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = HttpClient {
        // Release assets redirect to the GitHub objects CDN
        followRedirects = true
        install(ContentNegotiation) { json(json) }
    }

    /** Own supervisor scope for the install session work (survives navigation). */
    private val installScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serializes event dispatching (callbacks arrive from multiple threads). */
    private val dispatchLock = Any()

    /** Installable decision from the last successful check (null until then). */
    @Volatile
    private var pendingInstallable: UpdateDecision.Installable? = null

    private var statusReceiverRegistered = false

    // ------------------------------------------------------------------
    // Installed identity (PackageManager — same pattern as LogsViewModel)
    // ------------------------------------------------------------------

    val installedVersionName: String by lazy {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }

    private val installedVersionCode: Long by lazy {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
        }.getOrDefault(0L)
    }

    private val deviceAbi: String? by lazy {
        CoreUpdateVerifier.deviceArtifactKey(Build.SUPPORTED_ABIS.firstOrNull())
    }

    private fun dispatch(event: AppUpdateEvent) = synchronized(dispatchLock) {
        _state.value = AppUpdateStateMachine.reduce(_state.value, event)
    }

    // ------------------------------------------------------------------
    // 1. Check
    // ------------------------------------------------------------------

    /**
     * Queries the mihomet releases API (no auth, HTTPS) and runs the pure
     * verification chain. Outcomes: UpToDate, UpdateAvailable (installable),
     * UpdateAvailableManual (newer release, chain incomplete → release-page
     * route only) or Error.
     */
    suspend fun checkForUpdate() = withContext(Dispatchers.IO) {
        dispatch(AppUpdateEvent.CheckStarted)
        val outcome = runCatching {
            val release = fetchLatestRelease().getOrThrow()
            val infoBytes = release.assets
                .firstOrNull { it.name == AppUpdateVerifier.VERSION_INFO_ASSET_NAME }
                ?.let { asset -> runCatching { fetchBytes(asset.browser_download_url) }.getOrNull() }
            AppUpdateVerifier.decideUpdate(
                release,
                installedVersionName = installedVersionName,
                installedVersionCode = installedVersionCode,
                abi = deviceAbi ?: "",
                versionInfoBytes = infoBytes
            )
        }

        outcome.fold(
            onFailure = { t ->
                Log.w(TAG, "Update check failed: ${t.message}")
                dispatch(AppUpdateEvent.CheckFailed(t.message ?: "network error"))
            },
            onSuccess = { decision ->
                when (decision) {
                    is UpdateDecision.NoUpdate ->
                        dispatch(AppUpdateEvent.CheckUpToDate(decision.currentVersion))

                    is UpdateDecision.ManualOnly -> {
                        Log.w(TAG, "Update ${decision.version} requires the manual route: ${decision.reason}")
                        dispatch(AppUpdateEvent.CheckUpdateAvailable(decision.version, manualOnly = true))
                    }

                    is UpdateDecision.Installable -> {
                        pendingInstallable = decision
                        clearStaleUpdateFiles(keepVersion = decision.version)
                        val cached = cachedVerifiedApk(decision)
                        if (cached != null) {
                            Log.i(TAG, "Verified APK for ${decision.version} already cached — skipping download")
                            dispatch(AppUpdateEvent.DownloadVerified(decision.version, cached.absolutePath))
                        } else {
                            dispatch(AppUpdateEvent.CheckUpdateAvailable(decision.version, manualOnly = false))
                        }
                    }
                }
            }
        )
    }

    private suspend fun fetchLatestRelease(): Result<GitHubRelease> = runCatching {
        httpClient.get(AppUpdateVerifier.RELEASES_API_LATEST) {
            header(HttpHeaders.UserAgent, HTTP_USER_AGENT)
            header(HttpHeaders.Accept, "application/vnd.github.v3+json")
        }.body()
    }

    private suspend fun fetchBytes(url: String): ByteArray {
        check(AppUpdateVerifier.isTrustedReleaseUrl(url)) { "untrusted release URL: $url" }
        return httpClient.get(url) {
            header(HttpHeaders.UserAgent, HTTP_USER_AGENT)
        }.body()
    }

    // ------------------------------------------------------------------
    // 2. Download + verify
    // ------------------------------------------------------------------

    /**
     * Streams the verified APK into the app-external cache with a hard size cap
     * (the GitHub API asset size), then verifies size + signing certificate.
     * Any failure deletes the partial download and lands in Error.
     */
    suspend fun downloadVerifiedUpdate() = withContext(Dispatchers.IO) {
        val decision = pendingInstallable
        if (decision == null) {
            // State lost (e.g. fresh process) — restart the flow with a fresh check.
            checkForUpdate()
            return@withContext
        }
        if (_state.value !is AppUpdateState.UpdateAvailable) return@withContext

        dispatch(AppUpdateEvent.DownloadStarted(decision.version))
        runCatching {
            val file = updateFile(decision.version)
            val total = streamTo(file, decision.apk) { percent ->
                dispatch(AppUpdateEvent.DownloadProgress(percent))
            }
            AppUpdateVerifier.verifyApkSize(decision.apk.size, total).getOrThrow()
            Log.i(TAG, "Downloaded ${decision.apk.name} ($total bytes, sha256=${fileSha256Hex(file)})")

            // Signing-certificate anchor: same certificate as the installed app or refuse.
            val apkCerts = extractArchiveSigningCertEncodings(file.absolutePath)
                ?: throw IllegalStateException("could not read the signing certificate of the downloaded APK")
            if (!AppUpdateVerifier.signingCertsMatch(installedSigningCertEncodings(), apkCerts)) {
                throw SecurityException(
                    "the downloaded APK is not signed with this app's certificate — refusing to install (use the release page)"
                )
            }
            file
        }.fold(
            onSuccess = { file ->
                dispatch(AppUpdateEvent.DownloadVerified(decision.version, file.absolutePath))
            },
            onFailure = { t ->
                updateFile(decision.version).delete()
                Log.w(TAG, "Update download refused: ${t.message}", t)
                dispatch(AppUpdateEvent.DownloadFailed(t.message ?: "download failed"))
            }
        )
    }

    private suspend fun streamTo(
        file: File,
        apk: AppUpdateVerifier.ApkAsset,
        onProgress: (Int) -> Unit
    ): Long {
        check(AppUpdateVerifier.isTrustedReleaseUrl(apk.url)) { "untrusted APK download URL: ${apk.url}" }
        var lastReported = -1
        return httpClient.prepareGet(apk.url) {
            header(HttpHeaders.UserAgent, HTTP_USER_AGENT)
        }.execute { response ->
            check(response.status.value in 200..299) {
                "HTTP ${response.status.value} while downloading ${apk.name}"
            }
            val channel = response.bodyAsChannel()
            file.parentFile?.mkdirs()
            file.outputStream().use { out ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read == -1) break
                    if (read > 0) {
                        total += read
                        // Hard cap: never buffer more than the declared asset size.
                        if (total > apk.size) {
                            throw IllegalStateException("download exceeded the declared asset size (${apk.size})")
                        }
                        out.write(buffer, 0, read)
                        val percent = ((total * 100) / apk.size).toInt()
                        if (percent != lastReported) {
                            lastReported = percent
                            onProgress(percent)
                        }
                    }
                }
                total
            }
        }
    }

    /** A previously downloaded APK that still passes size + certificate verification. */
    private fun cachedVerifiedApk(decision: UpdateDecision.Installable): File? {
        val file = updateFile(decision.version)
        if (!file.exists() || file.length() != decision.apk.size) return null
        val apkCerts = extractArchiveSigningCertEncodings(file.absolutePath) ?: return null
        if (!AppUpdateVerifier.signingCertsMatch(installedSigningCertEncodings(), apkCerts)) return null
        return file
    }

    private fun clearStaleUpdateFiles(keepVersion: String) {
        val dir = updateCacheDir() ?: return
        dir.listFiles { f -> f.name.startsWith(UPDATE_FILE_PREFIX) }?.forEach { f ->
            if (!f.name.contains(keepVersion)) {
                runCatching { f.delete() }
            }
        }
    }

    private fun updateCacheDir(): File? =
        (context.externalCacheDir ?: context.cacheDir)?.apply { mkdirs() }

    private fun updateFile(version: String): File {
        val safeVersion = version.filter {
            it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == '+'
        }
        return File(updateCacheDir(), "$UPDATE_FILE_PREFIX$safeVersion-${deviceAbi ?: "unknown"}.apk")
    }

    private fun fileSha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------
    // 3. Install (PackageInstaller session + user consent)
    // ------------------------------------------------------------------

    /**
     * Installs the downloaded, verified APK. Handles the Android 8–12
     * "install unknown apps" gate by directing the user to the system settings
     * ([openInstallPermissionSettings]) — never bypassed, never retried
     * silently. The session then goes through the standard user-consent flow:
     * the system shows its confirmation dialog (PENDING_USER_ACTION →
     * ACTION_CONFIRM_INSTALL) and reports back on the install-status receiver.
     */
    fun installReadyUpdate() {
        when (val current = _state.value) {
            is AppUpdateState.NeedInstallPermission -> {
                val decision = pendingInstallable ?: return
                val file = updateFile(decision.version)
                if (!file.exists()) {
                    Log.w(TAG, "Staged update vanished — forcing a fresh check")
                    dispatch(AppUpdateEvent.Reset)
                    return
                }
                dispatch(AppUpdateEvent.InstallPermissionGranted(decision.version, file.absolutePath))
            }
            is AppUpdateState.ReadyToInstall -> Unit
            else -> return
        }

        if (!canRequestInstalls()) {
            val version = (_state.value as? AppUpdateState.ReadyToInstall)?.version ?: return
            dispatch(AppUpdateEvent.InstallPermissionNeeded(version))
            return
        }

        val ready = _state.value as? AppUpdateState.ReadyToInstall ?: return
        val decision = pendingInstallable ?: return
        dispatch(AppUpdateEvent.InstallCommitStarted)
        installScope.launch {
            runCatching { commitInstall(File(ready.apkPath), decision.apk.size) }
                .onFailure { t ->
                    Log.w(TAG, "Install session failed: ${t.message}", t)
                    dispatch(AppUpdateEvent.InstallCommitFailed(t.message ?: "installation failed"))
                }
        }
    }

    private fun canRequestInstalls(): Boolean = runCatching {
        context.packageManager.canRequestPackageInstalls()
    }.getOrDefault(false)

    // Broad catch inside is intentional: any commit failure (security, IO, session
    // state) must abandon the session and unregister the receiver before rethrow.
    @Suppress("TooGenericExceptionCaught")
    private fun commitInstall(apkFile: File, expectedSize: Long) {
        // Re-verify the staged APK immediately before the session (cache tampering guard).
        AppUpdateVerifier.verifyApkSize(expectedSize, apkFile.length()).getOrThrow()
        val apkCerts = extractArchiveSigningCertEncodings(apkFile.absolutePath)
            ?: throw IllegalStateException("could not read the signing certificate of the staged APK")
        if (!AppUpdateVerifier.signingCertsMatch(installedSigningCertEncodings(), apkCerts)) {
            throw SecurityException("staged APK is not signed with this app's certificate — refusing to install")
        }

        val installer = context.packageManager.packageInstaller
        val sessionId = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        var session: PackageInstaller.Session? = null
        try {
            session = installer.openSession(sessionId)
            session.openWrite("Mihomet-update.apk", 0, apkFile.length()).use { out ->
                apkFile.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            registerInstallStatusReceiver()
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName),
                flags
            )
            session.commit(callback.intentSender)
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            unregisterInstallStatusReceiver()
            throw t
        } finally {
            session?.close()
        }
    }

    private val installStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
            Log.i(TAG, "Install status callback: $status")
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // The system wants the user to confirm — launch ONLY the
                    // platform's own confirm-install dialog.
                    val confirm = extractConfirmIntent(intent)
                    if (confirm == null) {
                        Log.w(TAG, "Install status carried an unexpected confirmation intent — ignored")
                        return
                    }
                    runCatching { ctx.startActivity(confirm) }
                        .onFailure { Log.w(TAG, "Could not launch the install confirmation dialog", it) }
                }

                PackageInstaller.STATUS_SUCCESS -> {
                    unregisterInstallStatusReceiver()
                    // The process is replaced right after a successful install.
                    dispatch(AppUpdateEvent.InstallConfirmed)
                }

                else -> {
                    unregisterInstallStatusReceiver()
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        ?: "installation failed (status $status)"
                    dispatch(AppUpdateEvent.InstallCommitFailed(message))
                }
            }
        }
    }

    /** Guards the system fill-in intent: only the platform confirm dialog may be launched. */
    private fun extractConfirmIntent(intent: Intent): Intent? {
        val confirm = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        } ?: return null
        if (confirm.action != CONFIRM_INSTALL_ACTION) return null
        if (confirm.component != null) return null
        return confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun registerInstallStatusReceiver() {
        if (statusReceiverRegistered) return
        ContextCompat.registerReceiver(
            context,
            installStatusReceiver,
            IntentFilter(ACTION_INSTALL_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        statusReceiverRegistered = true
    }

    private fun unregisterInstallStatusReceiver() {
        if (!statusReceiverRegistered) return
        statusReceiverRegistered = false
        runCatching { context.unregisterReceiver(installStatusReceiver) }
    }

    // ------------------------------------------------------------------
    // Manual routes
    // ------------------------------------------------------------------

    /** Always-available fallback: open the GitHub releases page in a browser. */
    fun openReleasePage() {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdateVerifier.RELEASES_PAGE_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "Could not open the release page", it) }
    }

    /** Redirect to the "install unknown apps" screen for this app (Android 8–12 gate). */
    fun openInstallPermissionSettings() {
        val uri = Uri.parse("package:${context.packageName}")
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { t ->
            Log.w(TAG, "MANAGE_UNKNOWN_APP_SOURCES not resolvable — falling back to app details", t)
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { Log.e(TAG, "Could not open any install-permission settings screen", it) }
        }
    }

    // ------------------------------------------------------------------
    // Signing certificates (installed app vs downloaded APK)
    // ------------------------------------------------------------------

    private fun installedSigningCertEncodings(): List<ByteArray> = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.map { it.toByteArray() } ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures?.map { it.toByteArray() } ?: emptyList()
        }
    }.getOrDefault(emptyList())

    private fun extractArchiveSigningCertEncodings(path: String): List<ByteArray>? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: return@runCatching null
            // Some OEM package parsers leave signingInfo empty for archive info —
            // fall back to the legacy signatures field before refusing.
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }?.takeIf { it.isNotEmpty() }
                ?: @Suppress("DEPRECATION") legacyArchiveSignatures(path)
        } else {
            legacyArchiveSignatures(path)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun legacyArchiveSignatures(path: String): List<ByteArray>? =
        context.packageManager.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
            ?.signatures?.map { it.toByteArray() }?.takeIf { it.isNotEmpty() }

    private companion object {
        const val TAG = "AppUpdateManager"
        const val HTTP_USER_AGENT = "Mihomet-Android/1.0"
        const val ACTION_INSTALL_STATUS = "org.mihomet.app.action.INSTALL_STATUS"

        /** Platform action of the fill-in dialog intent (pre-34 constant string). */
        const val CONFIRM_INSTALL_ACTION = "android.content.pm.action.CONFIRM_INSTALL"

        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        const val UPDATE_FILE_PREFIX = "mihomet-update-v"
    }
}
