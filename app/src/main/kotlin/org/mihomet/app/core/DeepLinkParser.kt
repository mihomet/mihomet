package org.mihomet.app.core

import java.net.URLDecoder
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Converts import deep links (happ://, clash://, v2rayng://, sing-box://, incy:// …)
 * into plain import payload for [org.mihomet.app.core.config.UniversalConfigConverter]:
 * either a subscription URL or raw content (URI list / Base64 / Xray JSON).
 *
 * Supported shapes:
 *  - clash://install-config?url=https%3A%2F%2F…        (CMFA; clashmeta:// and flclash:// alike)
 *  - v2rayng://install-sub?url=…  v2rayng://import-sub?url=…
 *  - sing-box://import-remote?url=…
 *  - happ://add/<base64 payload>                        (one-tap share links)
 *  - happ://routing/add/<base64 JSON>, happ://routing/onadd/<base64 JSON>
 *                                                       (routing profile as decoded JSON)
 *  - happ://crypt4/…, happ://crypt5/…                   (proprietary crypto — verbatim, converter warns)
 *  - incy://add/<percent-encoded url>                   (subscription URL carried in the path)
 *  - incy://import/<url>                                (http URL returned as-is)
 *  - incy://import/<base64>                             (decoded payload content)
 *  - incy://crypt1/…                                    (proprietary crypto — verbatim, converter warns)
 *  - incy://<…> and anything else — passed through as text
 *
 * Pure string parsing (no android.net.Uri) so the logic is unit-testable on the JVM.
 */
object DeepLinkParser {

    private val urlParamActions = setOf(
        "install-config", "install-sub", "import-sub", "import-remote", "add-subscription"
    )

    fun parse(input: String?): String? {
        if (input.isNullOrBlank()) return null
        val trimmed = input.trim()
        if (!trimmed.contains("://")) return null

        val scheme = trimmed.substringBefore("://").substringAfterLast(' ').lowercase()
        val rest = trimmed.substringAfter("://")
        // "clash://install-config?url=…" has no path segment — default to the whole rest
        val pathAndQuery = if (rest.contains('/')) rest.substringAfter('/') else rest
        val query = pathAndQuery.substringAfter('?', "")
        val action = pathAndQuery.substringBefore('/').substringBefore('?').lowercase()

        val params = query.split("&")
            .filter { it.contains('=') }
            .associate {
                val kv = it.split("=", limit = 2)
                kv[0].lowercase() to runCatching { URLDecoder.decode(kv[1], "UTF-8") }.getOrDefault(kv[1])
            }

        // Panels/apps pass a remote subscription URL as the `url` parameter
        // (clash / clashmeta / flclash install-config, v2rayng install-sub, sing-box import-remote)
        if ((action in urlParamActions || params.containsKey("url")) && params["url"] != null) {
            val url = params["url"]!!.takeIf { it.startsWith("http") }
            if (url != null) return url
        }

        return when (scheme) {
            "happ" -> parseHapp(rest, trimmed)
            "incy" -> parseIncy(rest, trimmed)
            else -> trimmed
        }
    }

    /**
     * happ:// links carry the payload in the URI path:
     *  - add/<base64>            — shared node/config payload
     *  - routing/add/<base64>    — routing profile as decoded JSON
     *  - routing/onadd/<base64>  — same routing profile, alternative verb
     *  - crypt4/…, crypt5/…      — proprietary crypto payloads, passed through verbatim
     *                            (the converter emits a warning for unsupported content)
     */
    private fun parseHapp(path: String, original: String): String = when {
        path.startsWith("add/", ignoreCase = true) ->
            path.substringAfter('/', "")
                .takeIf { it.isNotBlank() }
                ?.let { decodeFlexibleBase64(it.substringBefore('?')) }
                ?: original
        path.startsWith("routing/", ignoreCase = true) -> {
            val sub = path.substringAfter('/', "")
            val kind = sub.substringBefore('/').lowercase()
            if (kind == "add" || kind == "onadd") {
                sub.substringAfter('/', "")
                    .takeIf { it.isNotBlank() }
                    ?.let { decodeFlexibleBase64(it.substringBefore('?')) }
                    ?: original
            } else {
                original
            }
        }
        path.startsWith("crypt4/", ignoreCase = true) -> original
        path.startsWith("crypt5/", ignoreCase = true) -> original
        else -> original
    }

    /**
     * incy:// links carry the payload in the URI path:
     *  - add/<percent-encoded url> — subscription URL, returned percent-decoded
     *  - import/<url>              — http(s) URL returned as-is
     *  - import/<base64>           — flexible base64, decoded content returned
     *  - crypt1/…                  — proprietary crypto payload, passed through verbatim
     */
    private fun parseIncy(path: String, original: String): String = when {
        path.startsWith("add/", ignoreCase = true) ->
            path.substringAfter('/', "")
                .takeIf { it.isNotBlank() }
                ?.let { if (it.contains('%')) percentDecoded(it) else it }
                ?.takeIf { it.isNotBlank() }
                ?: original
        path.startsWith("import/", ignoreCase = true) -> {
            val data = path.substringAfter('/', "")
            when {
                data.isBlank() -> original
                data.contains('%') -> {
                    val decoded = percentDecoded(data)
                    when {
                        decoded.startsWith("http", ignoreCase = true) -> decoded
                        else -> decodeFlexibleBase64(decoded) ?: original
                    }
                }
                data.startsWith("http", ignoreCase = true) -> data
                else -> decodeFlexibleBase64(data) ?: original
            }
        }
        path.startsWith("crypt1/", ignoreCase = true) -> original
        else -> original
    }

    private fun percentDecoded(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun decodeFlexibleBase64(payload: String): String? {
        val clean = payload.replace("\n", "").replace("\r", "").replace(" ", "")
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return runCatching {
            String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        }.recoverCatching {
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        }.getOrNull()
    }
}

/**
 * Bridge for payloads arriving from VIEW/SEND intents before the Profiles
 * screen is reached (singleTop activity → delivered via onNewIntent).
 * A Channel (not StateFlow) so rapid successive links are not conflated.
 */
object DeepLinkBus {
    private val pending = kotlinx.coroutines.channels.Channel<String>(capacity = 8)
    val flow: Flow<String> get() = pending.receiveAsFlow()

    fun post(text: String?) {
        text?.let { runCatching { pending.trySend(it) } }
    }

    fun consume() {
        // receiveAsFlow consumes on collect; nothing to drain here
    }
}
