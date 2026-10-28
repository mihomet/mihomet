package org.mihomet.app.core

import java.net.URLDecoder
import java.util.Base64

/**
 * Converts import deep links (happ://, clash://, v2rayng://, sing-box://, incy:// …)
 * into plain import payload for [org.mihomet.app.core.config.UniversalConfigConverter]:
 * either a subscription URL or raw content (URI list / Base64 / Xray JSON).
 *
 * Supported shapes:
 *  - clash://install-config?url=https%3A%2F%2F…        (CMFA / FlClash)
 *  - v2rayng://install-sub?url=…  v2rayng://import-sub?url=…
 *  - sing-box://import-remote?url=…
 *  - happ://add/<base64 payload>                        (one-tap share links)
 *  - happ://routing/<…>                                 (kept verbatim, converter parses it)
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
        if ((action in urlParamActions || params.containsKey("url")) && params["url"] != null) {
            val url = params["url"]!!.takeIf { it.startsWith("http") }
            if (url != null) return url
        }

        return when (scheme) {
            "happ" -> {
                val path = rest
                when {
                    path.startsWith("add/", ignoreCase = true) ->
                        path.substringAfter('/', "")
                            .takeIf { it.isNotBlank() }
                            ?.let { decodeFlexibleBase64(it.substringBefore('?')) }
                            ?: trimmed
                    else -> trimmed
                }
            }
            else -> trimmed
        }
    }

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
 */
object DeepLinkBus {
    private val pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val flow: kotlinx.coroutines.flow.StateFlow<String?> get() = pending

    fun post(text: String?) {
        pending.value = text
    }

    fun consume() {
        pending.value = null
    }
}
