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

    /**
     * Hardened SnakeYAML factory for user/panel-supplied YAML:
     * - maxAliasesForCollections raised above the default 50 — real panel
     *   templates (Remnawave "Quantum" et al.) legitimately use more anchors,
     *   and the default aborts the whole parse with YamlError;
     * - code point limit raised for the multi-thousand-line RU bundles.
     */
    fun newYaml(): org.yaml.snakeyaml.Yaml {
        val opts = org.yaml.snakeyaml.LoaderOptions().apply {
            // Real panel templates (Remnawave "Quantum" et al.) use more than the
            // default 50 anchors — the default aborts the entire parse.
            maxAliasesForCollections = 2000
            codePointLimit = 16 * 1024 * 1024
        }
        return org.yaml.snakeyaml.Yaml(opts)
    }

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
