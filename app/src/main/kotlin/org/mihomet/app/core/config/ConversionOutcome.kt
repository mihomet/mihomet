package org.mihomet.app.core.config

/**
 * Result of a subscription/config conversion.
 *
 * @param yaml     resulting YAML, or the original content when conversion was not possible
 * @param warnings non-fatal problems collected during conversion, as typed
 * [ConversionWarning]s (unrecognized nodes, per-proxy parse errors, format
 * errors that fell back to the raw input). Localize them at display time via
 * [WarningLocalizer]; never persist or ship them as raw text — use
 * [ConversionWarning.toStableKey] where a string form is unavoidable.
 */
data class ConversionOutcome(
    val yaml: String,
    val warnings: List<ConversionWarning> = emptyList()
)
