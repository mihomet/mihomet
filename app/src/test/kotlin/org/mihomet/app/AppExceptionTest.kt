package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mihomet.app.core.AppErrorCode
import org.mihomet.app.core.AppException
import org.mihomet.app.core.appExceptionOrNull
import org.mihomet.app.core.sourcePayloadOrNull
import org.mihomet.app.core.withConversionContext
import org.mihomet.app.core.config.ConversionIssue
import org.mihomet.app.core.config.ConversionIssueCode
import org.mihomet.app.core.config.ConversionReport
import org.mihomet.app.core.config.ConversionWarning

class AppExceptionTest {

    @Test
    fun `source payload survives an AppException cause chain without entering message`() {
        val payload = "vless://secret@example.com:443"
        val root = AppException(AppErrorCode.IMPORT_NO_COMPATIBLE_CONFIG)
        val wrapped = AppException(AppErrorCode.IMPORT_FAILED, cause = root)
            .withConversionContext(payload)

        assertEquals(payload, wrapped.sourcePayloadOrNull())
        assertSame(root, wrapped.cause?.cause)
        assertEquals("IMPORT_FAILED", wrapped.message?.substringBefore(':'))
        assertEquals(-1, wrapped.message?.indexOf("secret"))
    }

    @Test
    fun `typed error lookup walks wrapped causes`() {
        val typed = AppException(AppErrorCode.ENC_SUB_UNKNOWN_KEY, "key-1")
        val wrapped = IllegalStateException("transport wrapper", typed)

        assertSame(typed, wrapped.appExceptionOrNull())
    }

    @Test
    fun `existing source still receives missing conversion report and warnings`() {
        val source = "synthetic source"
        val report = ConversionReport(format = "xray", sourceNodes = 1)
        val warnings = listOf(ConversionWarning.NoNodesXray)
        val original = AppException(AppErrorCode.IMPORT_FAILED, sourcePayload = source)
        val failure = original.withConversionContext("fallback source", report, warnings)

        assertEquals(source, failure.sourcePayload)
        assertSame(report, failure.conversionReport)
        assertSame(warnings, failure.conversionWarnings)
        assertSame(original, failure.cause)
    }

    @Test
    fun `existing conversion context takes precedence over fallback context`() {
        val report = ConversionReport(format = "xray", sourceNodes = 1)
        val warnings = listOf(ConversionWarning.NoNodesXray)
        val failure = AppException(AppErrorCode.IMPORT_FAILED,
            sourcePayload = "source", conversionReport = report, conversionWarnings = warnings)

        assertSame(failure, failure.withConversionContext("fallback", ConversionReport(), emptyList()))
    }

    @Test
    fun `adding source retains conversion report and warnings`() {
        val issue = ConversionIssue(ConversionIssueCode.UNSUPPORTED_FIELD, "outbounds[0].streamSettings")
        val report = ConversionReport(format = "xray", issues = listOf(issue))
        val warnings = listOf(ConversionWarning.XrayLoss(issue))
        val failure = AppException(
            AppErrorCode.IMPORT_FAILED,
            conversionReport = report,
            conversionWarnings = warnings
        ).withConversionContext("synthetic source")

        assertSame(report, failure.conversionReport)
        assertSame(warnings, failure.conversionWarnings)
    }
}
