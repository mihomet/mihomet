package org.mihomet.app.data.subs

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mihomet.app.R
import org.mihomet.app.core.HappCrypto
import org.mihomet.app.core.HwidProvider
import org.mihomet.app.core.config.ConversionOutcome
import org.mihomet.app.core.config.UniversalConfigConverter
import org.mihomet.app.data.db.ProfileDao
import org.mihomet.app.data.db.ProfileEntity
import org.mihomet.app.data.db.ProfileType
import org.mihomet.app.data.crypto.SecretsCipher
import org.mihomet.app.data.preferences.SettingsRepository
import org.mihomet.app.ui.screens.profiles.SubscriptionMeta
import org.mihomet.app.ui.screens.profiles.SubscriptionMetaParser
import org.mihomet.app.ui.screens.profiles.SubscriptionUserinfo
import org.mihomet.app.ui.screens.profiles.SubscriptionUserinfoParser
import java.io.File
import java.util.Base64

/**
 * Result of a subscription download + conversion: mihomo YAML (with converter
 * warnings), the panel quota header (if any) and the panel meta headers
 * (profile-title / profile-update-interval / New-Url).
 */
data class SubscriptionDownload(
    val outcome: ConversionOutcome,
    val userinfo: SubscriptionUserinfo? = null,
    val meta: SubscriptionMeta = SubscriptionMeta()
)

/**
 * Successful profile refresh: the updated (and persisted) entity plus the
 * conversion outcome, so callers can surface converter warnings — converter
 * losses are never silent.
 */
data class ProfileRefreshResult(
    val profile: ProfileEntity,
    val outcome: ConversionOutcome
)

/**
 * Single owner of the subscription fetch pipeline: UA fallback chain, `x-hwid`
 * device header, 20 MB size cap, Base64 decoding and conversion to mihomo YAML.
 * Also handles Happ encrypted subscriptions (AES-128-GCM: tag in `encrypt-tag`,
 * key id in the URL, key material from the [SettingsRepository] keyring) and
 * applies refreshes to remote [ProfileEntity]s (file rewrite + DB update).
 */
class SubscriptionRepository(
    private val context: Context,
    private val profileDao: ProfileDao,
    private val settingsRepository: SettingsRepository
) {

    private val httpClient = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 15000
            requestTimeoutMillis = 30000
        }
    }

    /**
     * Stable device id sent as `x-hwid` with every subscription request
     * (required by the Happ/Incy panel ecosystem; user-provided header wins).
     */
    private val hwid: String by lazy { HwidProvider.get(context) }

    /**
     * Downloads [url] with [headersJson] custom headers, decodes Base64 payloads
     * and converts the content to mihomo YAML. Throws when every attempt failed
     * or the payload came back empty; the full UA fallback chain is exhausted first.
     */
    suspend fun downloadAndConvert(url: String, headersJson: String = "{}"): SubscriptionDownload =
        withContext(Dispatchers.IO) {
            val parsedHeaders = runCatching {
                Json.decodeFromString<Map<String, String>>(headersJson)
            }.getOrElse {
                headersJson.lines()
                    .map { it.trim() }
                    .filter { it.contains(":") || it.contains("=") }
                    .associate { line ->
                        val delimiter = if (line.contains(":")) ":" else "="
                        val parts = line.split(delimiter, limit = 2)
                        parts[0].trim() to parts.getOrElse(1) { "" }.trim()
                    }
            }

            val customUa = parsedHeaders.entries
                .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
            // Honest UA first: strict SRR panels (Remnawave) serve content by UA tier, so
            // "Mihomet" must land in the Mihomo tier — no fake Happ priority. The other
            // well-known clients stay as fallbacks for panels that gate on them.
            val userAgentsToTry = if (!customUa.isNullOrBlank()) {
                listOf(customUa)
            } else {
                listOf(
                    APP_USER_AGENT,
                    "Happ/3.6.0 (Android; Xray)",
                    "sing-box/1.10.0 (Android)",
                    "ClashMeta/1.18.0",
                    "v2rayNG/1.8.19"
                )
            }

            val hasCustomHwid = parsedHeaders.keys.any { it.equals(HwidProvider.HWID_HEADER, ignoreCase = true) }

            var responseText = ""
            var userinfo: SubscriptionUserinfo? = null
            var meta = SubscriptionMeta()
            var lastException: Exception? = null
            var encryptTag: String? = null

            for (ua in userAgentsToTry) {
                try {
                    val resp = httpClient.get(url) {
                        header("User-Agent", ua)
                        // x-hwid accompanies every attempt (user-provided header wins)
                        if (!hasCustomHwid) {
                            header(HwidProvider.HWID_HEADER, hwid)
                        }
                        parsedHeaders.forEach { (k, v) ->
                            if (!k.equals("User-Agent", ignoreCase = true)) {
                                header(k, v)
                            }
                        }
                    }
                    if (resp.status.value in 200..299) {
                        val contentLength = resp.headers["Content-Length"]?.toLongOrNull()
                        if (contentLength != null && contentLength > MAX_SUBSCRIPTION_BYTES) {
                            throw IllegalStateException(context.getString(R.string.profiles_too_large))
                        }
                        val body: String = resp.body()
                        if (body.isNotBlank()) {
                            responseText = body
                            // Happ encrypted subscriptions: the GCM tag rides in this
                            // header (Ktor lookup is case-insensitive), the key id in
                            // the URL (?key=keyNN) and the key material in the keyring.
                            encryptTag = resp.headers[HappCrypto.ENCRYPT_TAG_HEADER]
                            // Ktor Headers lookup is case-insensitive; format:
                            // "upload=123 download=456 total=789 expire=1700000000"
                            userinfo = SubscriptionUserinfoParser.parse(resp.headers["Subscription-Userinfo"])
                            // Happ/Incy panel metadata: profile-title, profile-update-interval, New-Url
                            meta = SubscriptionMetaParser.parse(
                                resp.headers.names().mapNotNull { name ->
                                    resp.headers[name]?.let { name to it }
                                }.toMap()
                            )
                            break
                        }
                    }
                } catch (e: Exception) {
                    lastException = e
                }
            }

            if (responseText.isBlank()) {
                if (lastException != null) throw lastException
                throw IllegalStateException("Empty or unsuccessful response from subscription URL")
            }

            // Happ encrypted subscription (AES-128-GCM): body = base64(ciphertext),
            // the GCM tag arrives separately in `encrypt-tag`, and the URL carries
            // only the key id (?key=keyNN) — the key itself lives in the client
            // keyring (out-of-band distribution). Decryption yields the plaintext
            // URI list / JSON directly, so no further Base64 decoding is needed.
            val trimmed = responseText.trim()
            val tag = encryptTag
            val decrypted: String? = if (tag != null) {
                val keyId = HappCrypto.keyIdFromUrl(url)
                    ?: throw IllegalArgumentException(
                        "Зашифрованная подписка: ключ не указан в URL (нет параметра ?key=keyNN)"
                    )
                val key = settingsRepository.getCryptoKeys()[keyId]
                    ?: throw IllegalArgumentException(
                        "Зашифрованная подписка: в настройках нет ключа $keyId"
                    )
                HappCrypto.decrypt(trimmed, tag, key.toByteArray(Charsets.UTF_8))
                    ?: throw IllegalArgumentException(
                        "Зашифрованная подписка: не удалось расшифровать ответ (ключ $keyId)"
                    )
            } else {
                null
            }

            // If Base64 encoded subscription, decode it
            val decoded = decrypted ?: if (!trimmed.startsWith("port:") &&
                !trimmed.startsWith("mixed-port:") &&
                !trimmed.startsWith("proxies:") &&
                !trimmed.startsWith("{") &&
                !trimmed.startsWith("[") &&
                !trimmed.startsWith("#")
            ) {
                runCatching {
                    val clean = trimmed.replace("\n", "").replace("\r", "").replace(" ", "").trim()
                    val padded = when (clean.length % 4) {
                        2 -> "$clean=="
                        3 -> "$clean="
                        else -> clean
                    }
                    val bytes = try {
                        Base64.getDecoder().decode(padded)
                    } catch (e: Exception) {
                        Base64.getUrlDecoder().decode(padded)
                    }
                    String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                }.getOrDefault(responseText)
            } else {
                responseText
            }

            UniversalConfigConverter.convertToMihomoYamlWithOutcome(decoded).let { outcome ->
                SubscriptionDownload(outcome = outcome, userinfo = userinfo, meta = meta)
            }
        }

    /**
     * Refreshes a remote profile: re-downloads the subscription, rewrites the
     * profile file and persists `lastUpdated`, the quota, the panel-initiated
     * URL migration (`New-Url`/`Fallback-Url`) and the panel update interval.
     * A missing `Subscription-Userinfo` / `profile-update-interval` header keeps
     * the previously stored values.
     */
    suspend fun refreshProfile(profile: ProfileEntity): Result<ProfileEntity> =
        refreshProfileWithOutcome(profile).map { it.profile }

    /**
     * Same as [refreshProfile] but also returns the [ConversionOutcome], letting
     * UI callers append the converter warnings suffix to their success message.
     */
    suspend fun refreshProfileWithOutcome(profile: ProfileEntity): Result<ProfileRefreshResult> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(performRefresh(profile))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private suspend fun performRefresh(profile: ProfileEntity): ProfileRefreshResult {
        // url / custom_headers are encrypted at rest — decrypt before use; the
        // migrated URL is re-encrypted on write below.
        val url = SecretsCipher.decrypt(profile.url)
        val headers = SecretsCipher.decrypt(profile.customHeaders)
        val download = downloadAndConvert(url, headers)
        File(profile.filePath).writeText(download.outcome.yaml)
        // If the server sent no Subscription-Userinfo this time, keep the previous quota.
        val quota = download.userinfo
        // Panel-initiated subscription relocation (New-Url / Fallback-Url): auto-migrate.
        val migratedUrl = download.meta.redirectUrl
            ?.takeIf { !it.equals(url, ignoreCase = true) } ?: url
        val updated = profile.copy(
            url = SecretsCipher.encrypt(migratedUrl),
            lastUpdated = System.currentTimeMillis(),
            uploadUsed = quota?.uploadUsed ?: profile.uploadUsed,
            downloadUsed = quota?.downloadUsed ?: profile.downloadUsed,
            trafficTotal = quota?.trafficTotal ?: profile.trafficTotal,
            expireAt = quota?.expireAt ?: profile.expireAt,
            // Absent profile-update-interval keeps the previously stored interval.
            updateIntervalHours = download.meta.updateIntervalHours
                .takeIf { it > 0 } ?: profile.updateIntervalHours
        )
        profileDao.updateProfile(updated)
        return ProfileRefreshResult(updated, download.outcome)
    }

    companion object {
        const val MAX_SUBSCRIPTION_BYTES = 20L * 1024 * 1024

        /** Minimum panel interval (hours) eligible for background auto-refresh; guards against overly chatty panels. */
        const val MIN_AUTO_UPDATE_HOURS = 6

        private const val MILLIS_PER_HOUR = 3_600_000L

        /**
         * Pure scheduling predicate (JVM-testable): a remote profile is due for a
         * background refresh when the panel-declared interval is at least
         * [MIN_AUTO_UPDATE_HOURS] and at least that interval has elapsed since
         * [ProfileEntity.lastUpdated] (boundary inclusive). LOCAL profiles and
         * REMOTE profiles without a URL are never due.
         */
        fun isDueForRefresh(profile: ProfileEntity, nowMillis: Long): Boolean {
            if (profile.type != ProfileType.REMOTE) return false
            if (profile.url.isBlank()) return false
            if (profile.updateIntervalHours < MIN_AUTO_UPDATE_HOURS) return false
            val intervalMillis = profile.updateIntervalHours * MILLIS_PER_HOUR
            return nowMillis - profile.lastUpdated >= intervalMillis
        }

        /**
         * Accepts either a JSON object string or a `Key: Value` / `Key=Value`
         * lines block and returns a JSON object string for [downloadAndConvert].
         */
        fun normalizeHeadersJson(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return "{}"
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                return trimmed
            }
            val map = trimmed.lines()
                .map { it.trim() }
                .filter { it.contains(":") || it.contains("=") }
                .associate { line ->
                    val delimiter = if (line.contains(":")) ":" else "="
                    val parts = line.split(delimiter, limit = 2)
                    parts[0].trim() to parts.getOrElse(1) { "" }.trim()
                }
            return Json.encodeToString(map)
        }

        // TODO: derive from BuildConfig.VERSION_NAME once buildFeatures { buildConfig = true }
        //  is enabled in app/build.gradle.kts (it is currently off, so the version is inlined).
        const val APP_VERSION = "1.0"
        const val APP_USER_AGENT = "Mihomet/$APP_VERSION (Android; Mihomo)"
    }
}
