package org.mihomet.app.core.config

import android.content.Context
import org.mihomet.app.R

/**
 * Maps typed [ConversionWarning]s to localized strings at display time.
 *
 * The mapping is driven entirely by the warning's TYPE — there is no string
 * matching anywhere (the old Russian-literal-prefix matching broke the EN UI
 * whenever a converter wording changed, external audit §3.9). Producers emit
 * typed warnings; this is the only place that turns them into user text.
 */
object WarningLocalizer {

    /**
     * Pure mapping: typed warning → `(string resource id, format args)`.
     * JVM-testable without an Android context; `null` args are substituted
     * with the localized "Unknown error" ([R.string.profiles_unknown_error])
     * by [localize] — they model a converter exception without a message.
     * [localize] is the thin Context-based wrapper around this.
     */
    fun resolve(warning: ConversionWarning): Pair<Int, List<String?>> = when (warning) {
        is ConversionWarning.UnrecognizedNode ->
            R.string.conv_warning_unrecognized to listOf(warning.uri)

        // Two display shapes: no reason → plain parse error,
        // reason present → "<uri> (<details>)"
        is ConversionWarning.ProxyParseError ->
            if (warning.reason != null) {
                R.string.conv_warning_parse_error_details to listOf(warning.uri, warning.reason)
            } else {
                R.string.conv_warning_parse_error to listOf(warning.uri)
            }

        is ConversionWarning.YamlError ->
            R.string.conv_error_yaml to listOf(warning.message)

        is ConversionWarning.XrayError ->
            R.string.conv_error_xray to listOf(warning.message)

        is ConversionWarning.SingBoxError ->
            R.string.conv_error_singbox to listOf(warning.message)

        is ConversionWarning.UnsupportedOutbound ->
            R.string.conv_warning_unsupported_outbound to listOf(warning.type, warning.tag)

        ConversionWarning.NoNodes ->
            R.string.conv_warning_no_nodes to emptyList()

        ConversionWarning.NoNodesXray ->
            R.string.conv_warning_no_nodes_xray to emptyList()

        ConversionWarning.NoNodesSingBox ->
            R.string.conv_warning_no_nodes_singbox to emptyList()

        ConversionWarning.UnknownFormat ->
            R.string.conv_warning_unknown_format to emptyList()

        is ConversionWarning.ProfileKeyDropped ->
            R.string.conv_warning_profile_key_dropped to listOf(warning.key)
    }

    /**
     * Localizes a single typed converter warning using [context] resources.
     * `null` message args (converter exceptions without a message) become
     * [R.string.profiles_unknown_error].
     */
    fun localize(warning: ConversionWarning, context: Context): String {
        val (resId, args) = resolve(warning)
        val resolved = args.map { it ?: context.getString(R.string.profiles_unknown_error) }
        return context.getString(resId, *resolved.toTypedArray())
    }
}
