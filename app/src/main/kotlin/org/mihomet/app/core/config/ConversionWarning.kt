package org.mihomet.app.core.config

/**
 * Typed, locale-independent warning produced by the config converters and the
 * runtime generator. This is the single currency warnings travel in — free
 * Russian literals must never cross a layer boundary (the old string-prefix
 * matching in WarningLocalizer leaked Russian into the EN UI whenever a
 * wording was edited, external audit §3.9).
 *
 * Display-time localization lives in [WarningLocalizer] (pure type →
 * @StringRes mapping + a thin Context wrapper). Wherever a warning must be
 * persisted or passed as a string (logs, future DB fields), use
 * [toStableKey] — a stable ASCII code + params, never localized text.
 */
sealed interface ConversionWarning {

    /** A share-link URI whose scheme is not recognized — the line was skipped. */
    data class UnrecognizedNode(val uri: String) : ConversionWarning

    /**
     * A recognized scheme that failed to parse — the line was skipped.
     * [reason] is the exception message, `null` when the parser failed without
     * details (display falls back to "Unknown error" via [WarningLocalizer]).
     */
    data class ProxyParseError(val uri: String, val reason: String? = null) : ConversionWarning

    /** Clash YAML could not be parsed; the original content was kept unchanged. */
    data class YamlError(val message: String?) : ConversionWarning

    /** Xray JSON conversion failed; the original content was kept unchanged. */
    data class XrayError(val message: String?) : ConversionWarning

    /** Sing-Box JSON conversion failed; the original content was kept unchanged. */
    data class SingBoxError(val message: String?) : ConversionWarning

    /** A Sing-Box outbound type the converter cannot represent — skipped. */
    data class UnsupportedOutbound(val type: String, val tag: String) : ConversionWarning

    /** No supported nodes found in the (URI-list) subscription. */
    data object NoNodes : ConversionWarning

    /** No supported nodes found in the Xray JSON config. */
    data object NoNodesXray : ConversionWarning

    /** No supported nodes found in the Sing-Box config. */
    data object NoNodesSingBox : ConversionWarning

    /** The payload matched no known configuration format; content passed through. */
    data object UnknownFormat : ConversionWarning

    /** Top-level profile key removed by the ConfigGenerator whitelist (audit P1 §2.1). */
    data class ProfileKeyDropped(val key: String) : ConversionWarning

    /**
     * Stable ASCII serialization: `code|param|param`. The ONLY form that may
     * leave the process or be persisted; localized text never does.
     */
    fun toStableKey(): String = when (this) {
        is UnrecognizedNode -> "unrecognized_node|$uri"
        is ProxyParseError -> "proxy_parse_error|$uri|${reason.orEmpty()}"
        is YamlError -> "yaml_error|${message.orEmpty()}"
        is XrayError -> "xray_error|${message.orEmpty()}"
        is SingBoxError -> "singbox_error|${message.orEmpty()}"
        is UnsupportedOutbound -> "unsupported_outbound|$type|$tag"
        NoNodes -> "no_nodes"
        NoNodesXray -> "no_nodes_xray"
        NoNodesSingBox -> "no_nodes_singbox"
        UnknownFormat -> "unknown_format"
        is ProfileKeyDropped -> "profile_key_dropped|$key"
    }

    companion object {
        /**
         * Best-effort inverse of [toStableKey] (for reading a persisted warning
         * back): codes are matched by prefix, the last field swallows any
         * remaining `|`-separated parts. Returns `null` for an unknown code.
         */
        fun fromStableKey(key: String): ConversionWarning? {
            val code = key.substringBefore('|')
            val body = key.substringAfter('|', "")
            return when (code) {
                "unrecognized_node" -> UnrecognizedNode(body)
                "proxy_parse_error" -> {
                    val uri = body.substringBefore('|')
                    val reason = body.substringAfter('|', "")
                    ProxyParseError(uri, reason.ifEmpty { null })
                }
                "yaml_error" -> YamlError(body.ifEmpty { null })
                "xray_error" -> XrayError(body.ifEmpty { null })
                "singbox_error" -> SingBoxError(body.ifEmpty { null })
                "unsupported_outbound" -> UnsupportedOutbound(
                    body.substringBefore('|'),
                    body.substringAfter('|', "")
                )
                "no_nodes" -> NoNodes
                "no_nodes_xray" -> NoNodesXray
                "no_nodes_singbox" -> NoNodesSingBox
                "unknown_format" -> UnknownFormat
                "profile_key_dropped" -> ProfileKeyDropped(body)
                else -> null
            }
        }
    }
}
