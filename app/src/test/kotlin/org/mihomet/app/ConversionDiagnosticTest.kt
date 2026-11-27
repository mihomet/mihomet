package org.mihomet.app

import org.junit.Assert.*
import org.junit.Test
import org.mihomet.app.core.AppErrorCode
import org.mihomet.app.core.config.*
import org.mihomet.app.ui.screens.profiles.ConversionDiagnostic
import org.mihomet.app.ui.screens.profiles.buildConversionDiagnosticText

class ConversionDiagnosticTest {
    @Test fun `partial report exports counts schema paths and issue codes`() {
        val report = ConversionReport(format = "xray", sourceNodes = 3, importedNodes = 2,
            issues = listOf(ConversionIssue(ConversionIssueCode.UNSUPPORTED_TRANSPORT, "outbounds[2].streamSettings.network")))
        val text = buildConversionDiagnosticText(ConversionDiagnostic(report), "1.3.8", listOf("Unsupported transport"))
        assertTrue(text.contains("Mihomet v1.3.8"))
        assertTrue(text.contains("\"sourceNodes\":3"))
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
}
