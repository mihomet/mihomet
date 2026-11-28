package org.mihomet.app.data.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mihomet.app.core.AppErrorCode
import org.mihomet.app.core.AppException
import org.mihomet.app.core.config.ConversionIssue
import org.mihomet.app.core.config.ConversionIssueCode
import org.mihomet.app.core.config.ConversionReport
import org.mihomet.app.core.config.ConversionWarning

class SubscriptionFailureSelectionTest {

    @Test
    fun `selected previous conversion failure keeps its source after a network error`() {
        val source = "previous conversion source"
        val report = ConversionReport(format = "xray", sourceNodes = 1)
        val warnings = listOf(ConversionWarning.NoNodesXray)
        val previous = AppException(
            AppErrorCode.IMPORT_NO_COMPATIBLE_CONFIG,
            sourcePayload = source,
            conversionReport = report,
            conversionWarnings = warnings
        )
        val networkError = IllegalStateException("network unavailable")

        val result = chainDownloadFailure(networkError, null, previous)

        assertEquals(AppErrorCode.IMPORT_NO_COMPATIBLE_CONFIG, result.code)
        assertEquals(source, result.sourcePayload)
        assertSame(report, result.conversionReport)
        assertSame(warnings, result.conversionWarnings)
        assertSame(networkError, result.cause)
    }

    @Test
    fun `selected previous conversion failure does not take payload from later generic failure`() {
        val previousSource = "previous conversion source"
        val laterSource = "later attempt source"
        val previousReport = ConversionReport(format = "xray", sourceNodes = 1)
        val previousWarnings = listOf(ConversionWarning.NoNodesXray)
        val previous = AppException(
            AppErrorCode.IMPORT_NO_COMPATIBLE_CONFIG,
            sourcePayload = previousSource,
            conversionReport = previousReport,
            conversionWarnings = previousWarnings
        )
        val laterFailure = AppException(AppErrorCode.IMPORT_FAILED, sourcePayload = laterSource)

        val result = chainDownloadFailure(laterFailure, laterSource, previous)

        assertEquals(AppErrorCode.IMPORT_NO_COMPATIBLE_CONFIG, result.code)
        assertEquals(previousSource, result.sourcePayload)
        assertSame(previousReport, result.conversionReport)
        assertSame(previousWarnings, result.conversionWarnings)
    }

    @Test
    fun `selected current typed failure keeps its own source and conversion context`() {
        val previous = AppException(
            AppErrorCode.IMPORT_NO_COMPATIBLE_CONFIG,
            sourcePayload = "previous source",
            conversionReport = ConversionReport(format = "xray", sourceNodes = 1),
            conversionWarnings = listOf(ConversionWarning.NoNodesXray)
        )
        val currentSource = "current conversion source"
        val currentReport = ConversionReport(
            format = "xray",
            issues = listOf(ConversionIssue(ConversionIssueCode.UNSUPPORTED_FIELD, "outbounds[0].protocol"))
        )
        val current = AppException(
            AppErrorCode.IMPORT_NO_COMPATIBLE_CONFIG,
            sourcePayload = currentSource,
            conversionReport = currentReport
        )

        val result = chainDownloadFailure(current, currentSource, previous)

        assertEquals(AppErrorCode.IMPORT_NO_COMPATIBLE_CONFIG, result.code)
        assertEquals(currentSource, result.sourcePayload)
        assertSame(currentReport, result.conversionReport)
        assertEquals(emptyList<ConversionWarning>(), result.conversionWarnings)
    }
}
