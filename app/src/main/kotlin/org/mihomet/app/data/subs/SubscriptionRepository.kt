package org.mihomet.app.data.subs

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteReadChannel
// Ktor 3: readAvailable(ByteArray) moved from the ByteReadChannel interface to
// an extension in ByteReadChannelOperationsKt — explicit import required.
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mihomet.app.BuildConfig
import org.mihomet.app.core.AppErrorCode
import org.mihomet.app.core.AppException
import org.mihomet.app.core.HappCrypto
import org.mihomet.app.core.HwidProvider
import org.mihomet.app.core.appExceptionOrNull
import org.mihomet.app.core.sourcePayloadOrNull
import org.mihomet.app.core.withConversionContext
import org.mihomet.app.core.config.ConfigGenerator
import org.mihomet.app.core.config.ConversionOutcome
import org.mihomet.app.core.config.UniversalConfigConverter
import org.mihomet.app.data.analytics.AnalyticsTracker
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

/**
 * Result of a subscription download + conversion: mihomo YAML (with converter
 * warnings), the panel quota header (if any) and the panel meta headers
 * (profile-title / profile-update-interval / New-Url).
 */
data class SubscriptionDownload(
    val outcome: ConversionOutcome,
    val userinfo: SubscriptionUserinfo? = null,
    val meta: SubscriptionMeta = SubscriptionMeta(),
    /** Original local input or decrypted remote subscription payload. */
    val sourcePayload: String? = null
) {
    override fun toString(): String =
        "SubscriptionDownload(format=${outcome.format}, sourcePayloadPresent=${sourcePayload != null})"
}

/**
 * Successful profile refresh: the updated (and persisted) entity plus the
 * conversion outcome, so callers can surface converter warnings — converter
 * losses are never silent.
 */
data class ProfileRefreshResult(
    val profile: ProfileEntity,
    val outcome: ConversionOutcome,
    val meta: SubscriptionMeta = SubscriptionMeta(),
    /** Original decrypted subscription payload used for this refresh. */
    val sourcePayload: String? = null
) {
    override fun toString(): String =
        "ProfileRefreshResult(format=${outcome.format}, sourcePayloadPresent=${sourcePayload != null})"
}

/**
 * Single owner of the subscription fetch pipeline: UA fallback chain, `x-hwid`
 * device header, 20 MB size cap, Base64 decoding and conversion to mihomo YAML.
 * Also handles Happ encrypted subscriptions (AES-128-GCM: tag in `encrypt-tag`,
 * key id in the URL, key material from the [SettingsRepository] keyring) and
 * applies refreshes to remote [ProfileEntity]s (file rewrite + DB update).
 */
// Fail-closed errors are typed AppException codes (core/AppError.kt, audit P2.9
// tail): the data layer never carries UI-language strings, the display layer
// localizes via AppErrorCode.stringRes().

/**
 * The subscription payload exceeds [SubscriptionRepository.MAX_SUBSCRIPTION_BYTES] —
 * whether the size was declared in `Content-Length` or discovered while streaming
 * a header-less (chunked) response. Deliberately aborts the whole download:
 * retrying the User-Agent fallback chain cannot make an oversized payload fit.
 */
internal class SubscriptionTooLargeException(message: String) : IllegalStateException(message)

/** Keeps the diagnostic context attached to whichever typed failure wins the retry chain. */
internal fun chainDownloadFailure(
    error: Throwable,
    sourcePayload: String?,
    previous: AppException?
): AppException {
    val typed = error.appExceptionOrNull()
    val previousTyped = previous?.appExceptionOrNull()
    val selected = when {
        typed != null && typed.code != AppErrorCode.IMPORT_FAILED -> typed
        previousTyped != null && previousTyped.code != AppErrorCode.IMPORT_FAILED -> previousTyped
        else -> typed ?: previousTyped
    }
    val selectedFromCurrent = selected != null && selected === typed
    val selectedSource = when {
        selectedFromCurrent -> selected.sourcePayload ?: sourcePayload ?: error.sourcePayloadOrNull()
        selected != null -> selected.sourcePayload ?: previous?.sourcePayloadOrNull()
        else -> sourcePayload ?: error.sourcePayloadOrNull() ?: previous?.sourcePayloadOrNull()
    }

    // Keep only selected error metadata from prior attempts. Chaining exceptions
    // would retain their response bodies, and payloads only describe this attempt.
    return AppException(
        selected?.code ?: AppErrorCode.IMPORT_FAILED,
        *(selected?.args ?: emptyArray()),
        cause = error,
        sourcePayload = selectedSource,
        conversionReport = selected?.conversionReport,
        conversionWarnings = selected?.conversionWarnings.orEmpty()
    )
}

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
    private val settingsRepository: SettingsRepository,
    private val analyticsTracker: AnalyticsTracker? = null
) {

    private val importMutex = Mutex()

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
     *
     * Header merge (every download path funnels through here — add/import,
     * manual refresh and the background worker): the profile's custom headers
     * win over the global identity settings (Settings → Subscriptions), which
     * win over the built-in defaults — see [SubscriptionRequestPlan.build].
     * Stored values are sanitized before they touch the wire
     * ([SubscriptionHeaders.sanitizeForRequest]), so a legacy row can never
     * smuggle an injected header line.
     */
    suspend fun downloadAndConvert(url: String, headersJson: String = "{}"): SubscriptionDownload =
        withContext(Dispatchers.IO) {
            val profileHeaders = SubscriptionHeaders.sanitizeForRequest(
                runCatching { SubscriptionHeaders.parseLenient(headersJson) }.getOrElse { emptyMap() }
            )
            // Global subscription identity (UA / x-hwid toggle / custom hwid).
            val identity = SubscriptionIdentitySettings.of(settingsRepository.settingsFlow.first())
            val plan = SubscriptionRequestPlan.build(profileHeaders, identity, hwid)
            val userAgentsToTry = plan.userAgentsToTry

            var chosenOutcome: ConversionOutcome? = null
            var userinfo: SubscriptionUserinfo? = null
            var meta = SubscriptionMeta()
            var lastException: AppException? = null
            var chosenSourcePayload: String? = null
            var lastConvertedOutcome: ConversionOutcome? = null
            // Content-aware acceptance: the first response that converts WITH
            // proxy-groups beats a group-less one (panels that serve a bare URI
            // list to unknown User-Agents). A group-less success is kept as a
            // fallback while the chain still has entries to try.
            var sawQuality = SubscriptionContentChoice.ChannelQuality.PLAIN

            for (ua in userAgentsToTry) {
                var attemptPayload: String? = null
                try {
                    // prepareGet().execute reads the body INSIDE the block through
                    // a streaming channel and closes the response on block exit —
                    // the payload is never buffered up-front, so the 20 MB cap
                    // applies to the bytes actually read, not to a fully
                    // downloaded response (external audit F05).
                    val attempt = httpClient.prepareGet(url) {
                        header("User-Agent", ua)
                        // x-hwid accompanies every attempt unless the merge
                        // resolved it to null (profile override / toggle off /
                        // custom override — see SubscriptionRequestPlan).
                        plan.hwidHeader?.let { header(HwidProvider.HWID_HEADER, it) }
                        plan.extraHeaders.forEach { (k, v) ->
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
                                AppErrorCode.SUBSCRIPTION_TOO_LARGE.name
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
                            AppErrorCode.SUBSCRIPTION_TOO_LARGE.name
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
                        // Keep the encrypted response available if key lookup or
                        // decryption fails before the converter can see a payload.
                        attemptPayload = attempt.text
                        val payload = decryptAttempt(attempt, url)
                        attemptPayload = payload
                        val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(payload)
                        lastConvertedOutcome = outcome
                        if (!ProfileValidation.isValid(outcome.yaml)) {
                            throw AppException(
                                AppErrorCode.IMPORT_NO_COMPATIBLE_CONFIG,
                                sourcePayload = payload,
                                conversionReport = outcome.report,
                                conversionWarnings = outcome.warnings
                            )
                        }
                        val decision = SubscriptionContentChoice.evaluate(
                            currentHasResponse = chosenOutcome != null,
                            currentSawQuality = sawQuality,
                            attemptEncrypted = false,
                            attemptText = outcome.yaml,
                            attemptReport = outcome.report,
                            currentReport = chosenOutcome?.report
                        )
                        if (decision.accept) {
                            chosenOutcome = outcome
                            userinfo = attempt.userinfo
                            meta = attempt.meta
                            chosenSourcePayload = payload
                            sawQuality = decision.sawQuality
                        }
                        if (decision.stop) break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SubscriptionTooLargeException) {
                    // Oversized for any User-Agent — retrying the chain is pointless.
                    val failure = AppException(
                        AppErrorCode.SUBSCRIPTION_TOO_LARGE,
                        cause = e,
                        sourcePayload = attemptPayload
                    )
                    throw failure
                } catch (e: Exception) {
                    lastException = chainDownloadFailure(e, attemptPayload, lastException)
                }
            }

            if (chosenOutcome == null) {
                lastConvertedOutcome?.let { outcome ->
                    analyticsTracker?.trackConversion(
                        outcome = outcome,
                        success = false
                    )
                }
                throw lastException ?: AppException(
                    AppErrorCode.IMPORT_FAILED
                )
            }

            analyticsTracker?.trackConversion(
                outcome = chosenOutcome,
                success = true
            )

            SubscriptionDownload(
                outcome = chosenOutcome,
                userinfo = userinfo,
                meta = meta,
                sourcePayload = chosenSourcePayload
            )
        }

    private suspend fun decryptAttempt(attempt: SubscriptionAttempt, url: String): String {
        val tag = attempt.encryptTag ?: return attempt.text
        val keyId = HappCrypto.keyIdFromUrl(url) ?: throw AppException(AppErrorCode.ENC_SUB_NO_KEY)
        val key = settingsRepository.getCryptoKeys()[keyId] ?: throw AppException(AppErrorCode.ENC_SUB_UNKNOWN_KEY, keyId)
        return HappCrypto.decrypt(attempt.text.trim(), tag, key.toByteArray(Charsets.UTF_8))
            ?: throw AppException(AppErrorCode.ENC_SUB_DECRYPT_FAILED, keyId)
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

    /** All import entry points share validation, encrypted metadata and atomic file persistence. */
    suspend fun addProfile(
        name: String,
        type: ProfileType,
        download: SubscriptionDownload,
        url: String = "",
        headers: String = "{}"
    ): ProfileRefreshResult = withContext(Dispatchers.IO) {
        importMutex.withLock {
            val outcome = enrichOutcome(download.outcome)
            if (!ProfileValidation.isValid(outcome.yaml)) {
                throw AppException(
                    AppErrorCode.REFRESH_REJECTED_INVALID_CONFIG,
                    sourcePayload = download.sourcePayload,
                    conversionReport = outcome.report,
                    conversionWarnings = outcome.warnings
                )
            }
            val effectiveUrl = download.meta.redirectUrl ?: url
            val encryptedUrl: String
            val encryptedHeaders: String
            try {
                encryptedUrl = SecretsCipher.encryptIfSecret(effectiveUrl)
                encryptedHeaders = SecretsCipher.encryptIfSecret(headers)
            } catch (e: SecretsCipher.SecretEncryptException) {
                throw AppException(
                    AppErrorCode.SUBSCRIPTION_ENCRYPT_FAILED,
                    cause = e,
                    sourcePayload = download.sourcePayload,
                    conversionReport = outcome.report,
                    conversionWarnings = outcome.warnings
                )
            }
            val directory = File(context.filesDir, "profiles").apply { mkdirs() }
            val file = File(directory, "profile_${java.util.UUID.randomUUID()}.yaml")
            val quota = download.userinfo
            val entity = ProfileEntity(
                name = name,
                type = type,
                url = encryptedUrl,
                filePath = file.absolutePath,
                customHeaders = encryptedHeaders,
                uploadUsed = quota?.uploadUsed ?: 0,
                downloadUsed = quota?.downloadUsed ?: 0,
                trafficTotal = quota?.trafficTotal ?: 0,
                expireAt = quota?.expireAt ?: 0,
                updateIntervalHours = download.meta.updateIntervalHours,
                conversionReport = outcome.report?.toJson().orEmpty()
            )
            try {
                atomicWriteText(file, outcome.yaml)
                val inserted = profileDao.insertProfileSelectingFirst(entity)
                ProfileRefreshResult(inserted, outcome, download.meta, download.sourcePayload)
            } catch (e: CancellationException) {
                file.delete()
                throw e
            } catch (e: AppException) {
                file.delete()
                throw e.withConversionContext(download.sourcePayload, outcome.report, outcome.warnings)
            } catch (e: Exception) {
                file.delete()
                throw AppException(
                    AppErrorCode.IMPORT_FAILED,
                    cause = e,
                    sourcePayload = download.sourcePayload,
                    conversionReport = outcome.report,
                    conversionWarnings = outcome.warnings
                )
            }
        }
    }

    private fun enrichOutcome(outcome: ConversionOutcome): ConversionOutcome = outcome.copy(
        warnings = (outcome.warnings + ConfigGenerator.profileKeyWarnings(outcome.yaml)).distinct()
    )

    private suspend fun performRefresh(profile: ProfileEntity): ProfileRefreshResult {
        // url / custom_headers are encrypted at rest — decrypt before use; the
        // migrated URL is re-encrypted on write below.
        val url = try {
            SecretsCipher.decrypt(profile.url)
        } catch (e: org.mihomet.app.data.crypto.SecretsCipher.SecretDecryptException) {
            throw AppException(AppErrorCode.SUBSCRIPTION_UNDECRYPTABLE, cause = e)
        }
        val headers = try {
            SecretsCipher.decrypt(profile.customHeaders)
        } catch (e: org.mihomet.app.data.crypto.SecretsCipher.SecretDecryptException) {
            throw AppException(AppErrorCode.SUBSCRIPTION_UNDECRYPTABLE, cause = e)
        }
        val download = downloadAndConvert(url, headers).let { it.copy(outcome = enrichOutcome(it.outcome)) }
        // Fail-closed: a refresh (including the background worker) must never
        // clobber a working profile with a degenerate payload (e.g. `proxies: []`
        // + `rules: []` from a broken panel) — the core would route everything
        // to DIRECT while the UI shows "connected". On rejection the profile
        // file and all metadata below are left untouched.
        if (!ProfileValidation.isValid(download.outcome.yaml)) {
            throw AppException(
                AppErrorCode.REFRESH_REJECTED_INVALID_CONFIG,
                sourcePayload = download.sourcePayload,
                conversionReport = download.outcome.report,
                conversionWarnings = download.outcome.warnings
            )
        }
        // Panel-initiated subscription relocation (New-Url / Fallback-Url): auto-migrate.
        val migratedUrl = download.meta.redirectUrl
            ?.takeIf { !it.equals(url, ignoreCase = true) } ?: url
        // External audit N07-2: encryption now fails closed
        // (SecretsCipher.SecretEncryptException) when the Keystore key is
        // unavailable while encrypted data exists. Encrypt BEFORE any write, so
        // a refusal leaves the profile file and the stored row untouched and
        // surfaces as Result.failure — the working subscription is never
        // clobbered with plaintext.
        val encryptedUrl = try {
            SecretsCipher.encrypt(migratedUrl)
        } catch (e: SecretsCipher.SecretEncryptException) {
            throw AppException(
                AppErrorCode.SUBSCRIPTION_ENCRYPT_FAILED,
                cause = e,
                sourcePayload = download.sourcePayload,
                conversionReport = download.outcome.report,
                conversionWarnings = download.outcome.warnings
            )
        }
        // If the server sent no Subscription-Userinfo this time, keep the previous quota.
        val quota = download.userinfo
        val updated = try {
            profileDao.updateProfileAfterRefresh(profile.id) { current ->
                // The row may have changed while HTTP was pending; never restore its old selection/settings.
                atomicWriteText(File(current.filePath), download.outcome.yaml)
                current.copy(
                    url = encryptedUrl,
                    lastUpdated = System.currentTimeMillis(),
                    uploadUsed = quota?.uploadUsed ?: current.uploadUsed,
                    downloadUsed = quota?.downloadUsed ?: current.downloadUsed,
                    trafficTotal = quota?.trafficTotal ?: current.trafficTotal,
                    expireAt = quota?.expireAt ?: current.expireAt,
                    conversionReport = download.outcome.report?.toJson().orEmpty(),
                    // A manual interval change made during the request wins over the panel suggestion.
                    updateIntervalHours = download.meta.updateIntervalHours
                        .takeIf { it > 0 && current.updateIntervalHours == profile.updateIntervalHours }
                        ?: current.updateIntervalHours
                )
            } ?: throw AppException(
                AppErrorCode.REFRESH_PROFILE_REMOVED,
                sourcePayload = download.sourcePayload
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppException) {
            throw e.withConversionContext(download.sourcePayload, download.outcome.report, download.outcome.warnings)
        } catch (e: Exception) {
            throw AppException(
                AppErrorCode.IMPORT_FAILED,
                cause = e,
                sourcePayload = download.sourcePayload,
                conversionReport = download.outcome.report,
                conversionWarnings = download.outcome.warnings
            )
        }
        return ProfileRefreshResult(updated, download.outcome, download.meta, download.sourcePayload)
    }

    companion object {
        const val MAX_SUBSCRIPTION_BYTES = 20L * 1024 * 1024

        /**
         * Crash-safe profile persistence (single owner of profile file writes):
         * writes [content] to a sibling temp file (`.tmp-<name>`) and renames it
         * over [target]. A failed rename leaves the existing profile intact;
         * concurrent writers use distinct temporary files.
         */
        internal fun atomicWriteText(target: File, content: String) {
            val tmp = File(target.parentFile, ".tmp-${target.name}-${java.util.UUID.randomUUID()}")
            try {
                tmp.writeText(content)
                if (!tmp.renameTo(target)) throw java.io.IOException("Atomic profile replacement failed")
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
        const val MIN_AUTO_UPDATE_HOURS = 1

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
         * Pure format normalization — content guards live in
         * [SubscriptionHeaders.parse] (strict, UI entry points) and
         * [SubscriptionHeaders.sanitizeForRequest] (wire path).
         */
        fun normalizeHeadersJson(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return "{}"
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                return trimmed
            }
            return SubscriptionHeaders.toJson(SubscriptionHeaders.parseLenient(raw))
        }

        val APP_VERSION: String = BuildConfig.VERSION_NAME
        val APP_USER_AGENT: String = "Mihomet/$APP_VERSION (Android; Mihomo)"

        /**
         * UA fallback chain used when neither the profile's custom headers nor
         * the global setting provide a User-Agent. Honest UA first (strict SRR
         * panels such as Remnawave serve content by UA tier), the other
         * well-known clients as fallbacks for panels that gate on them.
         */
        val UA_FALLBACK_CHAIN = listOf(
            APP_USER_AGENT,
            // Remnawave-type panels serve a full Mihomo YAML (with groups) to this
            // tier while unknown clients get a bare URI list — the content-aware
            // acceptance below walks the chain until a grouped response appears.
            "mihomo/v1.19.33",
            "Happ/3.6.0 (Android; Xray)",
            "sing-box/1.10.0 (Android)",
            "ClashMeta/1.18.0",
            "v2rayNG/1.8.19"
        )
    }
}
