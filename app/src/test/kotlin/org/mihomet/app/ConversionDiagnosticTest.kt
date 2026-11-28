package org.mihomet.app

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mihomet.app.core.AppErrorCode
import org.mihomet.app.core.AppException
import org.mihomet.app.core.withConversionContext
import org.mihomet.app.core.config.*
import org.mihomet.app.ui.screens.profiles.ConversionDiagnostic
import org.mihomet.app.ui.screens.profiles.appendConversionDiagnostic
import org.mihomet.app.ui.screens.profiles.buildConversionDiagnosticText
import org.mihomet.app.ui.screens.profiles.buildConversionFailureDiagnostic

class ConversionDiagnosticTest {
    @Test fun `partial report exports counts schema paths and issue codes`() {
        val report = ConversionReport(format = "xray", sourceNodes = 3, importedNodes = 2,
            alternativeRules = 7,
            issues = listOf(ConversionIssue(ConversionIssueCode.UNSUPPORTED_TRANSPORT, "outbounds[2].streamSettings.network")))
        val text = buildConversionDiagnosticText(ConversionDiagnostic(report), "1.3.8", listOf("Unsupported transport"))
        assertTrue(text.contains("Mihomet v1.3.8"))
        assertTrue(text.contains("\"sourceNodes\":3"))
        assertTrue(text.contains("\"alternativeRules\":7"))
        assertTrue(text.contains("UNSUPPORTED_TRANSPORT"))
        assertTrue(text.contains("outbounds[2].streamSettings.network"))
        assertTrue(text.contains("Unsupported transport"))
    }

    @Test fun `failed imports export without needing a saved profile`() {
        val diagnostic = ConversionDiagnostic(failureCode = AppErrorCode.IMPORT_NO_COMPATIBLE_CONFIG)
        val text = buildConversionDiagnosticText(diagnostic, "1.3.8", emptyList())
        assertTrue(text.contains("Failure: IMPORT_NO_COMPATIBLE_CONFIG"))
    }

    @Test fun `warning export never includes source credentials names or parser excerpts`() {
        val secret = "VERY_PRIVATE_123"
        val diagnostic = ConversionDiagnostic(warnings = listOf(
            ConversionWarning.UnrecognizedNode("vless://$secret@host"),
            ConversionWarning.ProxyParseError("ss://$secret@host", "bad password $secret"),
            ConversionWarning.YamlError("password: $secret"),
            ConversionWarning.XrayError("token=$secret"),
            ConversionWarning.SingBoxError("secret=$secret"),
            ConversionWarning.UnsupportedOutbound(secret, secret),
            ConversionWarning.ProfileKeyDropped(secret)
        ))
        val text = buildConversionDiagnosticText(diagnostic, "1.3.8", emptyList())
        assertFalse(text.contains(secret))
        assertFalse(text.contains("vless://"))
        assertFalse(text.contains("ss://"))
        assertTrue(text.contains("proxy_parse_error"))
        assertTrue(text.contains("yaml_error"))
    }

    @Test fun `sensitive source export preserves skipped entry while safe diagnostics omit credentials`() {
        val importedCredential = "11111111-2222-4333-8444-555555555555"
        val skippedCredential = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        val skippedEntry = """{"tag":"unsupported-kcp","protocol":"vless","settings":{"address":"skipped.example","port":443,"id":"$skippedCredential"},"streamSettings":{"network":"kcp"}}"""
        val source = """{
  "outbounds": [
    {"tag":"imported-tcp","protocol":"vless","settings":{"address":"imported.example","port":443,"id":"$importedCredential"}},
    $skippedEntry
  ]
}"""
        val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(source)
        val report = requireNotNull(outcome.report)
        val diagnostic = ConversionDiagnostic(
            report = report,
            warnings = outcome.warnings,
            sourcePayload = source
        )

        assertEquals(2, report.sourceNodes)
        assertEquals(1, report.importedNodes)
        assertEquals(1, report.skippedNodes)
        assertTrue(outcome.yaml.contains(importedCredential))
        assertFalse(outcome.yaml.contains(skippedCredential))
        assertTrue(report.issues.any { it.code == ConversionIssueCode.UNSUPPORTED_TRANSPORT })

        val safe = buildConversionDiagnosticText(diagnostic, "1.3.8", emptyList())
        val sensitive = buildConversionDiagnosticText(
            diagnostic,
            "1.3.8",
            emptyList(),
            includeSource = true
        )

        assertFalse(safe.contains(importedCredential))
        assertFalse(safe.contains(skippedCredential))
        assertTrue(safe.contains("UNSUPPORTED_TRANSPORT"))
        assertEquals("$source\n", sensitive.substringAfter("Source payload:\n"))
        assertTrue(sensitive.contains(skippedEntry))
    }

    @Test fun `source payload is omitted by default and included only explicitly`() {
        val source = "vless://secret-token@example.com:443"
        val diagnostic = ConversionDiagnostic(sourcePayload = source)

        val safe = buildConversionDiagnosticText(diagnostic, "1.3.8", emptyList())
        val sensitive = buildConversionDiagnosticText(
            diagnostic,
            "1.3.8",
            emptyList(),
            includeSource = true
        )

        assertFalse(safe.contains(source))
        assertFalse(safe.contains("Source payload"))
        assertTrue(sensitive.contains("Source payload"))
        assertTrue(sensitive.contains(source))
        assertFalse(diagnostic.toString().contains(source))
    }

    @Test fun `diagnostic can append source directly to a streaming destination`() {
        val diagnostic = ConversionDiagnostic(
            failureCode = AppErrorCode.IMPORT_NO_COMPATIBLE_CONFIG,
            sourcePayload = "large synthetic source"
        )
        val expected = buildConversionDiagnosticText(diagnostic, "1.3.8", emptyList(), includeSource = true)
        val actual = StringBuilder()

        appendConversionDiagnostic(actual, diagnostic, "1.3.8", emptyList(), includeSource = true)

        assertEquals(expected, actual.toString())
    }

    @Test fun `profile B failure without a body replaces profile A report without inheriting context`() {
        val profileASource = "vless://profile-a-secret@example.com:443"
        val profileADiagnostic = ConversionDiagnostic(
            report = ConversionReport(format = "xray", sourceNodes = 2, importedNodes = 1),
            warnings = listOf(ConversionWarning.ProfileKeyDropped("listeners")),
            sourcePayload = profileASource
        )
        val failuresWithoutBody = listOf(
            AppException(AppErrorCode.SUBSCRIPTION_TOO_LARGE) to AppErrorCode.SUBSCRIPTION_TOO_LARGE,
            java.io.IOException("Profile B failed before receiving a body") to AppErrorCode.IMPORT_FAILED
        )

        for ((failure, expectedCode) in failuresWithoutBody) {
            // A finishes while B is pending, then B fails before reading a response body.
            val reportFlow = MutableStateFlow<ConversionDiagnostic?>(profileADiagnostic)
            reportFlow.value = buildConversionFailureDiagnostic(failure)
            val diagnostic = requireNotNull(reportFlow.value)

            assertEquals(expectedCode, diagnostic.failureCode)
            assertNull(diagnostic.sourcePayload)
            assertNull(diagnostic.report)
            assertTrue(diagnostic.warnings.isEmpty())
            assertTrue(diagnostic.safeWarnings.isEmpty())
            val export = buildConversionDiagnosticText(diagnostic, "1.3.8", emptyList(), includeSource = true)
            assertFalse(export.contains(profileASource))
            assertFalse(export.contains("Source payload"))
        }
    }

    @Test fun `wrapped failure preserves its own operation source report and warnings`() {
        val profileBSource = "vless://profile-b-secret@example.com:443"
        val profileBReport = ConversionReport(format = "xray", sourceNodes = 3, importedNodes = 2)
        val profileBWarnings = listOf(ConversionWarning.ProfileKeyDropped("listeners"))
        val failure = AppException(AppErrorCode.SUBSCRIPTION_ENCRYPT_FAILED)
            .withConversionContext(profileBSource, profileBReport, profileBWarnings)
        val wrapped = IllegalStateException("Profile B persistence failed", failure)

        val staleProfileADiagnostic = ConversionDiagnostic(
            report = ConversionReport(format = "xray", sourceNodes = 2, importedNodes = 1),
            warnings = listOf(ConversionWarning.ProfileKeyDropped("listeners")),
            sourcePayload = "vless://profile-a-secret@example.com:443"
        )
        val reportFlow = MutableStateFlow<ConversionDiagnostic?>(staleProfileADiagnostic)
        reportFlow.value = buildConversionFailureDiagnostic(wrapped)
        val diagnostic = requireNotNull(reportFlow.value)

        assertEquals(AppErrorCode.SUBSCRIPTION_ENCRYPT_FAILED, diagnostic.failureCode)
        assertEquals(profileBSource, diagnostic.sourcePayload)
        assertSame(profileBReport, diagnostic.report)
        assertSame(profileBWarnings, diagnostic.warnings)
        val export = buildConversionDiagnosticText(diagnostic, "1.3.8", emptyList(), includeSource = true)
        assertTrue(export.contains(profileBSource))
    }
}
