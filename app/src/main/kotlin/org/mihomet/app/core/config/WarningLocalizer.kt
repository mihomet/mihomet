package org.mihomet.app.core.config

import android.content.Context
import org.mihomet.app.R

/**
 * Localizes [UniversalConfigConverter] warnings at display time.
 *
 * The converter runs outside Android and collects warnings as Russian literals
 * (see the `warnings?.add(...)` call sites in UniversalConfigConverter.kt), so
 * before surfacing them in the UI they must be matched against the known
 * warning prefixes and rebuilt from string resources. Warnings that do not
 * match any known pattern are returned as-is (pass-through), so new converter
 * warnings degrade gracefully instead of disappearing.
 */
object WarningLocalizer {

    /**
     * Converter's fallback for a missing exception message; the localized
     * replacement is [R.string.profiles_unknown_error] (EN "Unknown error").
     */
    private const val FALLBACK_UNKNOWN = "неизвестная ошибка"

    // Exact literals / prefixes emitted by UniversalConfigConverter.
    private const val PREFIX_UNRECOGNIZED = "Не удалось распознать узел: "
    private const val PREFIX_PARSE_ERROR = "Ошибка разбора узла: "
    private const val PREFIX_XRAY_ERROR = "Ошибка конвертации Xray JSON: "
    private const val PREFIX_SINGBOX_ERROR = "Ошибка конвертации Sing-Box JSON: "
    private const val PREFIX_YAML_ERROR = "Ошибка YAML: "

    private val NO_NODES_WARNINGS = mapOf(
        "В подписке не найдено ни одного поддерживаемого узла" to R.string.conv_warning_no_nodes,
        "В Xray-конфиге не найдено ни одного поддерживаемого узла" to R.string.conv_warning_no_nodes_xray,
        "В Sing-Box конфиге не найдено ни одного поддерживаемого узла" to R.string.conv_warning_no_nodes_singbox,
        "Не удалось определить формат конфигурации" to R.string.conv_warning_unknown_format
    )

    // "Тип outbound '$type' ('$tag') не поддерживается конвертером"
    private val UNSUPPORTED_OUTBOUND =
        Regex("^Тип outbound '(.+?)' \\('(.+)'\\) не поддерживается конвертером$")

    /**
     * Localizes a single converter warning using [context] resources.
     * Unrecognized warnings are returned unchanged.
     */
    fun localize(warning: String, context: Context): String {
        val (resId, args) = localizePattern(warning) ?: return warning
        val resolved = args.map { arg ->
            if (arg == FALLBACK_UNKNOWN) context.getString(R.string.profiles_unknown_error) else arg
        }
        return context.getString(resId, *resolved.toTypedArray())
    }

    /**
     * Pure matcher: maps a converter warning to `(string resource id, format args)`.
     * Returns `null` when the warning is not recognized — JVM-testable without an
     * Android context; [localize] is the thin Context-based wrapper around it.
     */
    fun localizePattern(warning: String): Pair<Int, List<String>>? {
        if (warning.isEmpty()) return null

        NO_NODES_WARNINGS[warning]?.let { return it to emptyList() }

        return when {
            warning.startsWith(PREFIX_UNRECOGNIZED) ->
                R.string.conv_warning_unrecognized to listOf(warning.removePrefix(PREFIX_UNRECOGNIZED))

            // Two shapes: "Ошибка разбора узла: <uri>" and "Ошибка разбора узла: <uri> (<details>)"
            warning.startsWith(PREFIX_PARSE_ERROR) -> {
                val rest = warning.removePrefix(PREFIX_PARSE_ERROR)
                val sep = rest.indexOf(" (")
                if (sep != -1 && rest.endsWith(")")) {
                    R.string.conv_warning_parse_error_details to listOf(
                        rest.substring(0, sep),
                        rest.substring(sep + 2, rest.length - 1)
                    )
                } else {
                    R.string.conv_warning_parse_error to listOf(rest)
                }
            }

            warning.startsWith(PREFIX_XRAY_ERROR) ->
                R.string.conv_error_xray to listOf(warning.removePrefix(PREFIX_XRAY_ERROR))

            warning.startsWith(PREFIX_SINGBOX_ERROR) ->
                R.string.conv_error_singbox to listOf(warning.removePrefix(PREFIX_SINGBOX_ERROR))

            warning.startsWith(PREFIX_YAML_ERROR) ->
                R.string.conv_error_yaml to listOf(warning.removePrefix(PREFIX_YAML_ERROR))

            else -> UNSUPPORTED_OUTBOUND.find(warning)?.let { m ->
                R.string.conv_warning_unsupported_outbound to listOf(m.groupValues[1], m.groupValues[2])
            }
        }
    }
}
