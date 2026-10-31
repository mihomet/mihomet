package org.mihomet.app.data.subs

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteReadChannel
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
import java.io.ByteArrayOutputStream
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
// Surfaced through Result.failure; the data exists but the Keystore key does not.
private const val SUBSCRIPTION_UNDECRYPTABLE_MESSAGE =
    "Не удалось расшифровать данные подписки — ключ шифрования недоступен или был заменён. Добавьте подписку заново."

// Surfaced through Result.failure when a refresh produced a semantically invalid
// config (see ProfileValidation): the last good profile file and its metadata are
// kept untouched, the refresh is rejected. Russian like the other data-layer errors.
private const val REFRESH_REJECTED_INVALID_CONFIG_MESSAGE =
    "Обновление отклонено: сервер вернул невалидную конфигурацию"

/**
 * The subscription payload exceeds [SubscriptionRepository.MAX_SUBSCRIPTION_BYTES] —
 * whether the size was declared in `Content-Length` or discovered while streaming
 * a header-less (chunked) response. Deliberately aborts the whole download:
 * retrying the User-Agent fallback chain cannot make an oversized payload fit.
 */
internal class SubscriptionTooLargeException(message: String) : IllegalStateException(message)

/**
 * Body text plus the headers of a successful subscription attempt, captured
 * inside the streaming [HttpClient.prepareGet] execute-block: the response is
 * closed as soon as the block returns, so nothing may be read from it later.
 */
private data class SubscriptionAttempt(
    val text: String,
    val encryptTag: String?,
    val userinfo: SubscriptionUserinfo?,
    val meta: SubscriptionMeta
)

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
                    // prepareGet().execute reads the body INSIDE the block through
                    // a streaming channel and closes the response on block exit —
                    // the payload is never buffered up-front, so the 20 MB cap
                    // applies to the bytes actually read, not to a fully
                    // downloaded response (external audit F05).
                    val attempt = httpClient.prepareGet(url) {
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
                    }.execute { resp ->
                        if (resp.status.value !in 200..299) return@execute null
                        // Fast-fail on a declared Content-Length (nothing read yet).
                        val contentLength = resp.headers["Content-Length"]?.toLongOrNull()
                        if (contentLength != null && contentLength > MAX_SUBSCRIPTION_BYTES) {
                            throw SubscriptionTooLargeException(
                                context.getString(R.string.profiles_too_large)
                            )
                        }
                        // Streamed read with a hard cap enforced on the actual bytes
                        // received, so it holds even when the server omits
                        // Content-Length (chunked transfer). Content-Encoding
                        // (gzip/deflate) is handled by the transport — OkHttp/Ktor
                        // decompress transparently, the channel yields plaintext.
                        val body = readBodyWithLimit(
                            resp.bodyAsChannel(),
                            MAX_SUBSCRIPTION_BYTES,
                            context.getString(R.string.profiles_too_large)
                        )
                        if (body.isBlank()) return@execute null
                        // All headers we keep must be captured inside this block:
                        // the response is closed as soon as it returns.
                        SubscriptionAttempt(
                            text = body,
                            encryptTag = resp.headers[HappCrypto.ENCRYPT_TAG_HEADER],
                            userinfo = SubscriptionUserinfoParser.parse(resp.headers["Subscription-Userinfo"]),
                            meta = SubscriptionMetaParser.parse(
                                resp.headers.names().mapNotNull { name ->
                                    resp.headers[name]?.let { name to it }
                                }.toMap()
                            )
                        )
                    }
                    if (attempt != null) {
                        responseText = attempt.text
                        encryptTag = attempt.encryptTag
                        userinfo = attempt.userinfo
                        meta = attempt.meta
                        break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SubscriptionTooLargeException) {
                    // Oversized for any User-Agent — retrying the chain is pointless.
                    throw e
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
        val url = try {
            SecretsCipher.decrypt(profile.url)
        } catch (e: org.mihomet.app.data.crypto.SecretsCipher.SecretDecryptException) {
            throw IllegalStateException(SUBSCRIPTION_UNDECRYPTABLE_MESSAGE, e)
        }
        val headers = try {
            SecretsCipher.decrypt(profile.customHeaders)
        } catch (e: org.mihomet.app.data.crypto.SecretsCipher.SecretDecryptException) {
            throw IllegalStateException(SUBSCRIPTION_UNDECRYPTABLE_MESSAGE, e)
        }
        val download = downloadAndConvert(url, headers)
        // Fail-closed: a refresh (including the background worker) must never
        // clobber a working profile with a degenerate payload (e.g. `proxies: []`
        // + `rules: []` from a broken panel) — the core would route everything
        // to DIRECT while the UI shows "connected". On rejection the profile
        // file and all metadata below are left untouched.
        if (!ProfileValidation.isValid(download.outcome.yaml)) {
            throw IllegalStateException(REFRESH_REJECTED_INVALID_CONFIG_MESSAGE)
        }
        atomicWriteText(File(profile.filePath), download.outcome.yaml)
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

        /**
         * Crash-safe profile persistence (single owner of profile file writes):
         * writes [content] to a sibling temp file (`.tmp-<name>`) and renames it
         * over [target], so a crash / OOM mid-write can never leave a torn
         * (half-written) profile file behind. `renameTo` can fail on some
         * filesystems (foreign mount points, exotic FUSE daemons) — in that case
         * the write degrades to a direct rewrite of [target] and the temp file
         * is removed.
         */
        internal fun atomicWriteText(target: File, content: String) {
            val tmp = File(target.parentFile, ".tmp-${target.name}")
            try {
                tmp.writeText(content)
                if (!tmp.renameTo(target)) {
                    target.writeText(content)
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }

        /** Streaming read granularity for [readBodyWithLimit]. */
        private const val STREAM_CHUNK_BYTES = 64 * 1024

        /**
         * Streams [channel] into a string, failing hard as soon as more than
         * [maxBytes] bytes have actually been received. The cap does not depend on
         * response headers: a chunked / no-Content-Length body is cut off at the
         * same limit instead of being buffered whole into memory (external audit
         * §9.3). Transport decompression (Content-Encoding) is transparent here —
         * OkHttp/Ktor hand over already-decoded bytes.
         *
         * Throws [SubscriptionTooLargeException] with [tooLargeMessage] on overflow.
         * Internal so JVM tests can drive it against a real HTTP server.
         */
        suspend fun readBodyWithLimitForTest(channel: io.ktor.utils.io.ByteReadChannel, maxBytes: Long, tooLarge: String): String =
            readBodyWithLimit(channel, maxBytes, tooLarge)

        internal suspend fun readBodyWithLimit(
            channel: ByteReadChannel,
            maxBytes: Long,
            tooLargeMessage: String
        ): String {
            val output = ByteArrayOutputStream(STREAM_CHUNK_BYTES)
            val buffer = ByteArray(STREAM_CHUNK_BYTES)
            var total = 0L
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read == -1) break
                if (read <= 0) continue
                total += read
                if (total > maxBytes) {
                    throw SubscriptionTooLargeException(tooLargeMessage)
                }
                output.write(buffer, 0, read)
            }
            return output.toString("UTF-8")
        }

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
