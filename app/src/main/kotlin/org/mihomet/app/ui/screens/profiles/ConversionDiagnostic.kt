package org.mihomet.app.ui.screens.profiles

import org.mihomet.app.core.AppErrorCode
import org.mihomet.app.core.config.ConversionReport
import org.mihomet.app.core.config.ConversionWarning

/** Source values and exception excerpts must never reach a shared diagnostic. */
data class ConversionDiagnostic(
    val report: ConversionReport? = null,
    val warnings: List<ConversionWarning> = emptyList(),
    val failureCode: AppErrorCode? = null
) {
    val safeWarnings: List<ConversionWarning>
        get() = (warnings + report?.issues.orEmpty().map(ConversionWarning::XrayLoss)).distinct().map { warning ->
            when (warning) {
                is ConversionWarning.XrayLoss -> warning
                is ConversionWarning.UnrecognizedNode -> ConversionWarning.UnrecognizedNode("[omitted]")
                is ConversionWarning.ProxyParseError -> ConversionWarning.ProxyParseError("[omitted]")
                is ConversionWarning.YamlError -> ConversionWarning.YamlError(null)
                is ConversionWarning.XrayError -> ConversionWarning.XrayError(null)
                is ConversionWarning.SingBoxError -> ConversionWarning.SingBoxError(null)
                is ConversionWarning.UnsupportedOutbound -> ConversionWarning.UnsupportedOutbound("[omitted]", "[omitted]")
                is ConversionWarning.ProfileKeyDropped -> ConversionWarning.ProfileKeyDropped("[omitted]")
                else -> warning
            }
        }
}

fun buildConversionDiagnosticText(
    diagnostic: ConversionDiagnostic,
    appVersion: String,
    descriptions: List<String>
): String = buildString {
    append("Mihomet v").append(appVersion).append(" — conversion report\n")
    diagnostic.failureCode?.let { append("Failure: ").append(it.name).append('\n') }
    diagnostic.report?.let { append(it.toJson()).append('\n') }
    diagnostic.safeWarnings.forEach { append(it.toStableKey()).append('\n') }
    descriptions.forEach { append(it).append('\n') }
}
