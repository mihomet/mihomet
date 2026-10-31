package org.mihomet.app.core.config

import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Shared primitives for the per-format converters behind
 * [UniversalConfigConverter]: the lenient JSON instance, the Base64 payload
 * decoding/detection used for subscription bodies and vmess/ss links, and the
 * `300s`-style interval parser shared by the Xray and Sing-Box converters.
 */
internal object ConverterSupport {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun decodeBase64(str: String): String {
        val clean = str.replace("\n", "").replace("\r", "").replace(" ", "").trim()
        val padded = when (clean.length % 4) {
            2 -> "$clean=="
            3 -> "$clean="
            else -> clean
        }
        return try {
            val bytes = Base64.getDecoder().decode(padded)
            String(bytes, StandardCharsets.UTF_8).trim()
        } catch (e: Exception) {
            val bytes = Base64.getUrlDecoder().decode(padded)
            String(bytes, StandardCharsets.UTF_8).trim()
        }
    }

    fun isLikelyBase64(str: String): Boolean {
        if (str.startsWith("{") || str.startsWith("[") || str.startsWith("proxies:") || str.startsWith("port:") || str.startsWith("mixed-port:")) {
            return false
        }
        val clean = str.replace("\n", "").replace("\r", "").replace(" ", "").trim()
        if (clean.length < 16) return false
        val validChars = clean.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' }
        return validChars && !clean.contains("://")
    }

    fun parseSeconds(str: String, default: Int): Int {
        val trimmed = str.trim().lowercase()
        return when {
            trimmed.endsWith("s") -> trimmed.removeSuffix("s").toIntOrNull() ?: default
            trimmed.endsWith("m") -> (trimmed.removeSuffix("m").toIntOrNull() ?: (default / 60)) * 60
            trimmed.endsWith("h") -> (trimmed.removeSuffix("h").toIntOrNull() ?: 1) * 3600
            else -> trimmed.toIntOrNull() ?: default
        }
    }
}
