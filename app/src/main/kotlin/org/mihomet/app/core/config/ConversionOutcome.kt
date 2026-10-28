package org.mihomet.app.core.config

/**
 * Result of a subscription/config conversion.
 *
 * @param yaml     resulting YAML, or the original content when conversion was not possible
 * @param warnings non-fatal problems collected during conversion: unrecognized nodes
 * ("Не удалось распознать узел: ..."), per-proxy parse errors and fatal fallbacks to raw
 * input ("Ошибка конвертации: ..." — marks a failed conversion).
 * The literals are Russian; localize them at display time via [WarningLocalizer].
 */
data class ConversionOutcome(
    val yaml: String,
    val warnings: List<String>
)
