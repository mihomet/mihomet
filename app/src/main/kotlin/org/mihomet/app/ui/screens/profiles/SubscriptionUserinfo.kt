package org.mihomet.app.ui.screens.profiles

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.Locale

/**
 * Quota/expiration info parsed from the `Subscription-Userinfo` HTTP response header.
 *
 * Header format (Clash/Mihomo convention):
 * `Subscription-Userinfo: upload=123 download=456 total=789 expire=1700000000`
 * - traffic values are bytes, [expire] is a Unix timestamp in **seconds**.
 * - Any field may be missing; missing fields stay at 0 (0 = unknown/unlimited).
 */
data class SubscriptionUserinfo(
    val uploadUsed: Long = 0,
    val downloadUsed: Long = 0,
    val trafficTotal: Long = 0,
    val expireAt: Long = 0
) {
    val trafficUsed: Long get() = uploadUsed + downloadUsed
}

object SubscriptionUserinfoParser {

    // 1e11 seconds ≈ year 5138; 1e11 ms ≈ 1973. Values below this are seconds, above — millis.
    private const val MILLIS_THRESHOLD = 100_000_000_000L

    /**
     * Parses the raw header value. Returns null when the header is absent/blank or contains
     * no recognizable key=value pairs, so callers can keep previously stored quota intact.
     */
    fun parse(raw: String?): SubscriptionUserinfo? {
        if (raw.isNullOrBlank()) return null

        var upload = 0L
        var download = 0L
        var total = 0L
        var expire = 0L
        var recognized = false

        for (token in raw.split(' ', ',', ';', '\t', '\n', '\r')) {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) continue
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            val key = trimmed.substring(0, eq).trim().lowercase(Locale.ROOT)
            val value = trimmed.substring(eq + 1).trim().toLongOrNull() ?: continue
            when (key) {
                "upload" -> { upload = value; recognized = true }
                "download" -> { download = value; recognized = true }
                "total" -> { total = value; recognized = true }
                "expire" -> { expire = value; recognized = true }
            }
        }

        if (!recognized) return null
        val expireMillis = when {
            expire <= 0L -> 0L
            expire >= MILLIS_THRESHOLD -> expire
            else -> expire * 1000L
        }
        return SubscriptionUserinfo(
            uploadUsed = upload.coerceAtLeast(0L),
            downloadUsed = download.coerceAtLeast(0L),
            trafficTotal = total.coerceAtLeast(0L),
            expireAt = expireMillis
        )
    }
}

/**
 * Human-readable traffic size, e.g. `3.2 GB`, `512.0 MB`, `42 B`.
 */
fun formatTrafficBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
    if (bytes <= 0L) return "0\u00A0B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    val tb = gb * 1024.0
    return when {
        bytes >= tb -> String.format(locale, "%.2f TB", bytes / tb)
        bytes >= gb -> String.format(locale, "%.1f GB", bytes / gb)
        bytes >= mb -> String.format(locale, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(locale, "%.1f KB", bytes / kb)
        else -> "$bytes B"
    }.replace(" ", "\u00A0") // non-breaking space: the unit never wraps onto its own line
}

/**
 * Subscription panel metadata parsed from the Happ/Incy-style HTTP response headers.
 *
 * Recognized headers:
 * - `profile-title` — display name of the profile. Incy prefixes encoded titles with
 *   `base64:`; the payload is decoded transparently (standard or URL-safe alphabet).
 * - `profile-update-interval` — panel-recommended update period in **hours**.
 *   Persisted to `ProfileEntity.updateIntervalHours` (Room v3) and honored by
 *   the background `SubscriptionRefreshWorker` (`data/subs/`).
 * - `New-Url` / `Fallback-Url` — panel-initiated subscription relocation
 *   ("auto-migration"): well-behaved clients are expected to store the new URL
 *   and fetch from it from now on. Only absolute http(s) URLs are accepted.
 *
 * Happ/Incy behavior headers (the panel remotely configures the client):
 * - `subscription-autoconnect` — `true`/`1` asks the client to make this profile
 *   the active one ([autoConnect]).
 * - `per-app-proxy-mode` — panel hint for per-app routing: `whitelist`/`blacklist`/
 *   `all` ([perAppModeHint], raw trimmed value as sent by the panel).
 * - `per-app-proxy-list` — package names for the per-app routing hint, sent either
 *   as a JSON array or as a multiline/comma-separated list ([perAppPackages]).
 * - `tun-mode` — explicit `true`/`false` hint whether the client should use a TUN
 *   interface ([tunModeHint] is `null` when the header is absent or unparseable).
 */
data class SubscriptionMeta(
    val profileTitle: String? = null,
    val updateIntervalHours: Int = 0,
    val redirectUrl: String? = null,
    val autoConnect: Boolean = false,
    val perAppModeHint: String? = null,
    val perAppPackages: List<String> = emptyList(),
    val tunModeHint: Boolean? = null
)

object SubscriptionMetaParser {

    private const val BASE64_PREFIX = "base64:"

    /** Values accepted by `subscription-autoconnect` as boolean true. */
    private val TRUE_VALUES = setOf("true", "1")

    /**
     * Parses meta headers from a plain header map. Header names are matched
     * case-insensitively, so any header lookup (e.g. ktor's case-insensitive
     * `Headers`) can be flattened into it before the call.
     */
    fun parse(headers: Map<String, String>): SubscriptionMeta {
        if (headers.isEmpty()) return SubscriptionMeta()

        val lookup = HashMap<String, String>(headers.size)
        for ((key, value) in headers) {
            if (key != null && value != null) {
                lookup.putIfAbsent(key.trim().lowercase(Locale.ROOT), value)
            }
        }

        val title = lookup["profile-title"]?.trim()?.takeIf { it.isNotEmpty() }?.let(::decodeTitle)
        val interval = lookup["profile-update-interval"]?.trim()?.toDoubleOrNull()
            ?.toInt()?.coerceAtLeast(0) ?: 0
        val redirect = sanitizeUrl(lookup["new-url"]) ?: sanitizeUrl(lookup["fallback-url"])

        // Behavior headers: header names case-insensitive, values kept as sent
        // (normalization is the caller's concern).
        val autoConnectRaw = lookup["subscription-autoconnect"]?.trim()
        val autoConnect = autoConnectRaw != null &&
            autoConnectRaw.lowercase(Locale.ROOT) in TRUE_VALUES
        val perAppModeHint = lookup["per-app-proxy-mode"]?.trim()?.takeIf { it.isNotEmpty() }
        val perAppPackages = parsePackageList(lookup["per-app-proxy-list"])
        val tunModeHint = when (lookup["tun-mode"]?.trim()?.lowercase(Locale.ROOT)) {
            "true" -> true
            "false" -> false
            else -> null
        }

        return SubscriptionMeta(
            profileTitle = title,
            updateIntervalHours = interval,
            redirectUrl = redirect,
            autoConnect = autoConnect,
            perAppModeHint = perAppModeHint,
            perAppPackages = perAppPackages,
            tunModeHint = tunModeHint
        )
    }

    /**
     * Decodes the Incy `base64:<payload>` title notation; plain titles pass through.
     * Undecodable payloads fall back to the raw text after the prefix.
     */
    private fun decodeTitle(raw: String): String {
        if (!raw.startsWith(BASE64_PREFIX, ignoreCase = true)) return raw
        val payload = raw.substring(BASE64_PREFIX.length).trim()
        if (payload.isEmpty()) return raw

        val clean = payload.replace("\n", "").replace("\r", "").replace(" ", "")
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        val decoded = runCatching {
            val bytes = try {
                Base64.getDecoder().decode(padded)
            } catch (e: IllegalArgumentException) {
                Base64.getUrlDecoder().decode(padded)
            }
            String(bytes, Charsets.UTF_8)
        }.getOrNull()

        return decoded?.trim()?.takeIf { it.isNotEmpty() } ?: payload
    }

    /** Only absolute http(s) URLs are legitimate migration targets. */
    private fun sanitizeUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            null
        }
    }

    /**
     * Parses the `per-app-proxy-list` header value: a JSON array of package
     * names (`["com.example.app","com.foo.bar"]`) or a multiline /
     * comma-separated list. Blank entries are dropped, duplicates collapsed;
     * unparseable input degrades to an empty list.
     */
    private fun parsePackageList(raw: String?): List<String> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyList()

        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            val decoded = runCatching {
                Json.decodeFromString(ListSerializer(String.serializer()), trimmed)
            }.getOrNull()
            if (decoded != null) {
                return decoded.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            }
        }

        // Multiline / comma-separated notation; stray brackets and quotes from
        // half-valid JSON are tolerated.
        return trimmed.trim('[', ']')
            .split('\n', '\r', ',')
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}
