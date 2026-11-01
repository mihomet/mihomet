package org.mihomet.app.data.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.mihomet.app.core.GitHubAsset
import org.mihomet.app.core.GitHubRelease
import java.security.MessageDigest

/**
 * Pure verification chain for in-app self-updates (audit P1.4) — same
 * fail-closed philosophy as the core updater
 * [org.mihomet.app.core.update.CoreUpdateVerifier], adapted to what the app
 * release pipeline publishes (`.github/workflows/release.yml`):
 *
 * Every release ships per-ABI APKs named `Mihomet-v<version>-<abi>.apk` plus a
 * machine-readable `version-info.json` asset:
 *
 * ```json
 * {"versionName": "1.2.2", "versionCode": 6}
 * ```
 *
 * The in-app updater may ONLY offer an install when, in order:
 *
 *  1. **Version** — the release tag parses to a semver strictly greater than
 *     the installed `versionName` ([AppVersion]).
 *  2. **version-info.json** — parses, is well-formed (semver versionName,
 *     positive versionCode), and its versionName matches the release tag it
 *     was downloaded from.
 *  3. **Monotonic versionCode** — the new versionCode is strictly greater than
 *     the installed one (Android refuses otherwise-identical installs; this
 *     also blocks replayed/rolled-back release assets).
 *  4. **Asset binding** — the APK asset is selected by an EXACT name that
 *     embeds the verified version (`Mihomet-v<version>[-<abi>].apk`), its
 *     declared size is positive, and its download URL is pinned to the
 *     mihomet/mihomet GitHub release space.
 *  5. **Integrity** — the downloaded file's byte size must equal the asset
 *     size reported by the GitHub API, and the APK must be signed with the
 *     SAME certificate as the currently installed app
 *     ([signingCertsMatch] — the cryptographic anchor; the PackageInstaller
 *     would refuse a signature mismatch anyway, but the check happens before
 *     any install session exists).
 *
 * Free of Android dependencies (except java.security) so the chain is
 * unit-testable on the JVM. Every step returns/throws inside [Result]-shaped
 * APIs: the updater installs NOTHING unless the whole chain succeeds.
 */
object AppUpdateVerifier {

    /** Human fallback route — always available, never gated by the chain. */
    const val RELEASES_PAGE_URL = "https://github.com/mihomet/mihomet/releases"

    /** GitHub API endpoint (no auth, HTTPS only — security invariant). */
    const val RELEASES_API_LATEST = "https://api.github.com/repos/mihomet/mihomet/releases/latest"

    /** Machine-readable version metadata asset published by release.yml. */
    const val VERSION_INFO_ASSET_NAME = "version-info.json"

    @Serializable
    data class AppVersionInfo(
        val versionName: String = "",
        val versionCode: Long = 0L,
        /** Optional future field (mirrors the core manifest): minimum version required to install. */
        val minAppVersion: String? = null
    )

    /** The release APK asset selected for this device. */
    data class ApkAsset(val name: String, val url: String, val size: Long)

    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------------
    // version-info.json
    // ------------------------------------------------------------------

    /**
     * Parses the version-info.json bytes. Format sanity: versionName must be
     * present AND semver-parseable, versionCode must be positive. Unknown
     * fields are tolerated (forward-compatible releases keep working).
     */
    fun parseVersionInfo(bytes: ByteArray): Result<AppVersionInfo> = runCatching {
        val info = json.decodeFromString<AppVersionInfo>(bytes.toString(Charsets.UTF_8))
        require(info.versionName.isNotBlank()) { "version-info.json has no versionName" }
        require(info.versionCode > 0L) { "version-info.json has no valid versionCode" }
        check(AppVersion.parse(info.versionName) != null) {
            "version-info.json versionName '${info.versionName}' is not a semantic version"
        }
        info
    }

    /**
     * Cross-checks version-info.json against the GitHub release tag it was
     * downloaded from — a mismatch means the release assets are inconsistent.
     * Tolerates the `v` prefix difference between tag and versionName.
     */
    fun checkVersionInfoMatchesTag(info: AppVersionInfo, releaseTag: String): Result<Unit> = runCatching {
        val tag = releaseTag.trim()
        val normalizedTag = tag.removePrefix("v").removePrefix("V")
        check(info.versionName.trim() == normalizedTag) {
            "version-info.json versionName '${info.versionName}' does not match release tag '$tag'"
        }
    }

    /**
     * Optional minimum-version gate (honored when version-info.json carries
     * `minAppVersion`): the INSTALLED version must already be at least
     * [AppVersionInfo.minAppVersion] for the in-app install to proceed.
     */
    fun checkMinAppVersion(info: AppVersionInfo, installedVersionName: String): Result<Unit> = runCatching {
        val min = info.minAppVersion?.trim().takeUnless { it.isNullOrEmpty() } ?: return@runCatching
        val current = AppVersion.parse(installedVersionName)
            ?: throw IllegalStateException("installed version '${installedVersionName}' is not parseable")
        val required = AppVersion.parse(min)
            ?: throw IllegalStateException("minAppVersion '$min' is not parseable")
        check(current >= required) {
            "update requires at least $min (installed $installedVersionName) — install manually"
        }
    }

    // ------------------------------------------------------------------
    // APK asset selection + download pinning
    // ------------------------------------------------------------------

    /**
     * Selects the release APK for [abi] by EXACT asset name that embeds the
     * verified [versionName]: `Mihomet-v<version>-<abi>.apk`, falling back to
     * the universal `Mihomet-v<version>.apk`. Refuses assets with no declared
     * size or a URL that does not end with the asset name.
     */
    fun selectApkAsset(assets: List<GitHubAsset>, versionName: String, abi: String): Result<ApkAsset> =
        runCatching {
            require(versionName.isNotBlank()) { "versionName is blank" }
            val escaped = Regex.escape(versionName.trim())
            val perAbi = Regex("^Mihomet-v$escaped-${Regex.escape(abi)}\\.apk$")
            val universal = Regex("^Mihomet-v$escaped\\.apk$")
            val asset = assets.firstOrNull { perAbi.matches(it.name) }
                ?: assets.firstOrNull { universal.matches(it.name) }
                ?: throw IllegalStateException(
                    "release has no APK asset for version $versionName (ABI $abi, " +
                            "have: ${assets.map { it.name }})"
                )
            require(asset.size > 0L) { "APK asset ${asset.name} declares no size" }
            require(asset.browser_download_url.endsWith("/${asset.name}")) {
                "APK asset ${asset.name} has a mismatching download URL"
            }
            ApkAsset(asset.name, asset.browser_download_url, asset.size)
        }

    /**
     * Defense in depth: asset URLs taken from the GitHub API response are only
     * followed when they point into the mihomet/mihomet release space. A
     * tampered API answer cannot redirect the download elsewhere.
     */
    fun isTrustedReleaseUrl(url: String): Boolean =
        url.startsWith("https://github.com/mihomet/mihomet/releases/") ||
                url.startsWith("https://api.github.com/repos/mihomet/mihomet/releases/")

    /** Exact downloaded-byte-size gate (GitHub API asset size as the anchor). */
    fun verifyApkSize(expectedSize: Long, actualSize: Long): Result<Unit> = runCatching {
        check(actualSize == expectedSize) {
            "downloaded APK size $actualSize does not match the release asset ($expectedSize)"
        }
    }

    // ------------------------------------------------------------------
    // Signing-certificate anchor
    // ------------------------------------------------------------------

    /**
     * The cryptographic anchor of the chain: the downloaded APK must be signed
     * with the SAME certificate as the currently installed app. Inputs are the
     * DER-encoded signing certificates (`GET_SIGNING_CERTIFICATES` /
     * `GET_SIGNATURES`) of the installed package and of the downloaded APK.
     * Certificates are compared by SHA-256 digest; ANY shared certificate
     * matches (tolerates key rotation with overlapping certificate history).
     * Empty inputs never match (fail-closed).
     */
    fun signingCertsMatch(installedCertEncodings: List<ByteArray>, apkCertEncodings: List<ByteArray>): Boolean {
        if (installedCertEncodings.isEmpty() || apkCertEncodings.isEmpty()) return false
        val installedDigests = installedCertEncodings.map { sha256Hex(it) }.toHashSet()
        return apkCertEncodings.any { sha256Hex(it) in installedDigests }
    }

    /** Lowercase hex SHA-256 of [bytes] (log/diagnostic helper). */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------
    // Full decision (check phase)
    // ------------------------------------------------------------------

    /**
     * Outcome of the update check. [ManualOnly] means a newer release exists
     * but the chain cannot anchor an in-app install — the UI only offers the
     * release-page route (the reason is logged, never silently swallowed).
     */
    sealed interface UpdateDecision {
        data class NoUpdate(val currentVersion: String) : UpdateDecision
        data class Installable(val version: String, val versionCode: Long, val apk: ApkAsset) : UpdateDecision
        data class ManualOnly(val version: String, val reason: String) : UpdateDecision
    }

    /**
     * Decides what the check means. [versionInfoBytes] is the already-downloaded
     * version-info.json asset (null when the release does not ship one).
     */
    fun decideUpdate(
        release: GitHubRelease,
        installedVersionName: String,
        installedVersionCode: Long,
        abi: String,
        versionInfoBytes: ByteArray?
    ): UpdateDecision {
        val tag = release.tag_name.trim()

        // 1. version: strictly newer semver only
        if (!AppVersion.isUpdateAvailable(installedVersionName, tag)) {
            return UpdateDecision.NoUpdate(installedVersionName)
        }

        // 2. version-info.json present, parseable, tag-bound
        if (versionInfoBytes == null) {
            return UpdateDecision.ManualOnly(tag, "version-info.json missing from release")
        }
        val info = parseVersionInfo(versionInfoBytes).getOrElse {
            return UpdateDecision.ManualOnly(tag, "version-info.json unreadable: ${it.message}")
        }
        checkVersionInfoMatchesTag(info, tag).getOrElse {
            return UpdateDecision.ManualOnly(tag, "version-info.json does not match the release tag")
        }

        // 3. monotonic versionCode (rollback/replay guard)
        if (info.versionCode <= installedVersionCode) {
            return UpdateDecision.NoUpdate(installedVersionName)
        }

        // optional min-version gate
        checkMinAppVersion(info, installedVersionName).getOrElse {
            return UpdateDecision.ManualOnly(tag, "minimum version gate: ${it.message}")
        }

        // 4. APK asset bound to the verified version + trusted URL
        val apk = selectApkAsset(release.assets, info.versionName, abi).getOrElse {
            return UpdateDecision.ManualOnly(tag, "no installable APK asset: ${it.message}")
        }
        if (!isTrustedReleaseUrl(apk.url)) {
            return UpdateDecision.ManualOnly(tag, "APK download URL is not a trusted release URL")
        }

        return UpdateDecision.Installable(info.versionName, info.versionCode, apk)
    }
}
