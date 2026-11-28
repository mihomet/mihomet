package org.mihomet.app.ui.screens.profiles

import org.mihomet.app.core.AppErrorCode
import org.mihomet.app.core.appExceptionOrNull
import org.mihomet.app.core.sourcePayloadOrNull
import org.mihomet.app.core.config.ConversionReport
import org.mihomet.app.core.config.ConversionWarning

/** Source values and exception excerpts must never reach a shared diagnostic. */
data class ConversionDiagnostic(
    val report: ConversionReport? = null,
    val warnings: List<ConversionWarning> = emptyList(),
    val failureCode: AppErrorCode? = null,
    /** Sensitive source is excluded from normal diagnostics and exports. */
    val sourcePayload: String? = null
) {
    override fun toString(): String =
        "ConversionDiagnostic(failureCode=$failureCode, sourcePayloadPresent=${sourcePayload != null})"

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

/** A shared UI report may belong to another operation; only the failure owns its context. */
internal fun buildConversionFailureDiagnostic(error: Throwable): ConversionDiagnostic {
    val typed = error.appExceptionOrNull()
    return ConversionDiagnostic(
        report = typed?.conversionReport,
        warnings = typed?.conversionWarnings.orEmpty(),
        failureCode = typed?.code ?: AppErrorCode.IMPORT_FAILED,
        sourcePayload = error.sourcePayloadOrNull()
    )
}

fun buildConversionDiagnosticText(
    diagnostic: ConversionDiagnostic,
    appVersion: String,
    descriptions: List<String>,
    includeSource: Boolean = false
): String = buildString {
    appendConversionDiagnostic(this, diagnostic, appVersion, descriptions, includeSource)
}

fun appendConversionDiagnostic(
    output: Appendable,
    diagnostic: ConversionDiagnostic,
    appVersion: String,
    descriptions: List<String>,
    includeSource: Boolean = false
) {
    output.append("Mihomet v").append(appVersion).append(" — conversion report\n")
    diagnostic.failureCode?.let { output.append("Failure: ").append(it.name).append('\n') }
    diagnostic.report?.let { output.append(it.toJson()).append('\n') }
    diagnostic.safeWarnings.forEach { output.append(it.toStableKey()).append('\n') }
    descriptions.forEach { output.append(it).append('\n') }
    if (includeSource) {
        diagnostic.sourcePayload?.let { output.append("Source payload:\n").append(it).append('\n') }
    }
}
