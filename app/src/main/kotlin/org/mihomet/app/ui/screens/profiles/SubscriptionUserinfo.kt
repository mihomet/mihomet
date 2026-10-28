package org.mihomet.app.ui.screens.profiles

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
    if (bytes <= 0L) return "0 B"
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
    }
}
